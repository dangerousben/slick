package slick.compiler

import slick.ast.Library.AggregateFunctionSymbol
import slick.{SlickException, SlickTreeException}
import slick.ast._
import slick.ast.QueryParameter.constOp
import slick.ast.Util._
import slick.ast.TypeUtil._
import slick.util.{Ellipsis, ??}

/** This phase merges nested nodes of types Bind, Filter, GroupBy, SortBy, Take, Drop and
  * CollectionCast to Comprehension nodes. Nodes can be merged if they occur in the following
  * order:
  *
  * [Source] -> Filter (where) -> GroupBy -> SortBy / Filter (having) -> Take / Drop
  *
  * Aliasing Binds and CollectionCasts are allowed everywhere in the chain. Any out of order
  * operation starts a new chain with a subquery as the source.
  */
class MergeToComprehensions extends Phase {
  val name = "mergeToComprehensions"

  /** A map from a TypeSymbol and a field on top of it to a new Symbol */
  type Replacements = Map[(TypeSymbol, Symbol), Symbol]

  type Mappings = Seq[((TypeSymbol, Symbol), List[Symbol])]

  def apply(state: CompilerState) = state.map(convert)

  def convert(tree: Node): Node = {
    // Find all references into tables so we can convert TableNodes to Comprehensions
    val tableFields =
      tree.collect { case Select(_ :@ NominalType(t: TableIdentitySymbol, _), f) => (t, f) }
        .groupBy(_._1).mapValues(_.map(_._2).distinct.toVector)
    logger.debug("Table fields: " + tableFields)

    /** Merge Take, Drop, Bind and CollectionCast into an existing Comprehension */
    def mergeTakeDrop(n: Node, buildBase: Boolean): (Comprehension, Replacements) = n match {
      case Take(f1, count1) =>
        val (c1, replacements1) = mergeTakeDrop(f1, true)
        logger.debug("Merging Take into Comprehension:", Ellipsis(n, List(0)))
        val count2 = applyReplacements(count1, replacements1, c1)
        val fetch2 = c1.fetch match {
          case Some(t) => Some(constOp[Long]("min")(math.min)(t, count2))
          case None => Some(count2)
        }
        val c2 = c1.copy(fetch = fetch2) :@ c1.nodeType
        logger.debug("Merged Take into Comprehension:", c2)
        (c2, replacements1)

      case Drop(f1, count1) =>
        val (c1, replacements1) = mergeTakeDrop(f1, true)
        logger.debug("Merging Drop into Comprehension:", Ellipsis(n, List(0)))
        val count2 = applyReplacements(count1, replacements1, c1)
        val (fetch2, offset2) = (c1.fetch, c1.offset) match {
          case (None,    None   ) => (None, Some(count2))
          case (Some(t), None   ) => (Some(constOp[Long]("max")(math.max)(LiteralNode(0L), constOp[Long]("-")(_ - _)(t, count2))), Some(count2))
          case (None,    Some(d)) => (None, Some(constOp[Long]("+")(_ + _)(d, count2)))
          case (Some(t), Some(d)) => (Some(constOp[Long]("max")(math.max)(LiteralNode(0L), constOp[Long]("-")(_ - _)(t, count2))), Some(constOp[Long]("+")(_ + _)(d, count2)))
        }
        val c2 = c1.copy(fetch = fetch2, offset = offset2) :@ c1.nodeType
        logger.debug("Merged Drop into Comprehension:", c2)
        (c2, replacements1)

      case n =>
        mergeCommon(mergeTakeDrop _, mergeSortBy _, n, buildBase, allowFilter = false)
    }

    /** Merge Bind, Filter (as WHERE or HAVING depending on presence of GROUP BY), CollectionCast
      * and SortBy into an existing Comprehension */
    def mergeSortBy(n: Node, buildBase: Boolean): (Comprehension, Replacements) = n match {
      case SortBy(s1, f1, b1) =>
        val (c1, replacements1) = mergeSortBy(f1, true)
        logger.debug("Merging SortBy into Comprehension:", Ellipsis(n, List(0)))
        val b2 = b1.map { case (n, o) => (applyReplacements(n, replacements1, c1), o) }
        val c2 = c1.copy(orderBy = b2 ++: c1.orderBy) :@ c1.nodeType
        logger.debug("Merged SortBy into Comprehension:", c2)
        (c2, replacements1)

      case n =>
        mergeCommon(mergeSortBy _, mergeGroupBy _, n, buildBase)
    }

    /** Merge GroupBy into an existing Comprehension or create new Comprehension from non-grouping Aggregation */
    def mergeGroupBy(n: Node, buildBase: Boolean): (Comprehension, Replacements) = n match {
      case Bind(s1, GroupBy(s2, f1, b1, ts1), Pure(str1, ts2)) =>
        val (c1, replacements1) = mergeFilterWhere(f1, true)
        logger.debug("Merging GroupBy into Comprehension:", Ellipsis(n, List(0, 0)))
        val b2 = applyReplacements(b1, replacements1, c1)
        val str2 = str1.replace {
          case Aggregate(_, FwdPath(s :: ElementSymbol(2) :: Nil), v) if s == s1 =>
            applyReplacements(v, replacements1, c1).replace {
              case Apply(f: AggregateFunctionSymbol, Seq(ch)) :@ tpe =>
                Apply(f, Seq(ch match {
                  case StructNode(Seq(ch, _*)) => ch._2
                  case n => n
                }))(tpe)
            }
          case FwdPath(s :: ElementSymbol(1) :: rest) if s == s1 =>
            rest.foldLeft(b2) { case (n, s) => n.select(s) }.nodeWithComputedType()
        }
        val c2 = c1.copy(groupBy = Some(b2), select = Some(Pure(str2, ts2))).nodeWithComputedType()
        logger.debug("Merged GroupBy into Comprehension:", c2)
        val StructNode(defs2) = str2
        val replacements = defs2.map { case (f, _) => (ts2, f) -> f }.toMap
        logger.debug("Replacements are: "+replacements)
        (c2, replacements)

      case n @ Pure(Aggregate(s1, f1, str1), ts) =>
        val (c1, replacements1) = mergeFilterWhere(f1, true)
        logger.debug("Merging Aggregate source into Comprehension:", Ellipsis(n, List(0, 0)))
        val str2 = applyReplacements(str1, replacements1, c1)
        val c2 = c1.copy(select = Some(Pure(str2, ts))).nodeWithComputedType()
        logger.debug("Merged Aggregate source into Comprehension:", c2)
        val StructNode(defs) = str2
        val repl = defs.map { case (f, _) => ((ts, f), f) }.toMap
        logger.debug("Replacements are: "+repl)
        (c2, repl)

      case n => mergeFilterWhere(n, buildBase)
    }

    /** Merge Bind, Filter (as WHERE), CollectionCast into an existing Comprehension */
    def mergeFilterWhere(n: Node, buildBase: Boolean): (Comprehension, Replacements) =
      mergeCommon(mergeFilterWhere _, convertBase _, n, buildBase)

    /** Build a base Comprehension from a non-Comprehension base (e.g. Join) or a sub-Comprehension */
    def convertBase(n: Node, buildBase: Boolean): (Comprehension, Replacements) = {
      val (n2, mappings) = {
        if(buildBase) createSourceOrTopLevel(n)
        else createSource(n).getOrElse(throw new SlickTreeException("Cannot convert node to SQL Comprehension", n))
      }
      buildSubquery(n2, mappings)
    }

    /** Lift a valid top-level or source Node into a subquery */
    def buildSubquery(n: Node, mappings: Mappings): (Comprehension, Replacements) = {
      logger.debug("Building new Comprehension from:", n)
      val newSyms = mappings.map(x => (x, new AnonSymbol))
      val s = new AnonSymbol
      val struct = StructNode(newSyms.map { case ((_, ss), as) => (as, FwdPath(s :: ss)) }.toVector)
      val pid = new AnonTypeSymbol
      val res = Comprehension(s, n, select = Some(Pure(struct, pid))).nodeWithComputedType()
      logger.debug("Built new Comprehension:", res)
      val replacements = newSyms.map { case (((ts, f), _), as) => ((ts, f), as) }.toMap
      logger.debug("Replacements are: "+replacements)
      (res, replacements)
    }

    /** Convert a Node for use as a source in a Join. Joins and TableNodes are not converted to
      * Comprehensions. Instead of returning regular replacements, the method returns identity
      * mappings for all fields in the source. */
    def createSource(n: Node): Option[(Node, Mappings)] = n match {
      case t: TableNode =>
        logger.debug("Creating source from TableNode:", t)
        val mappings = tableFields.getOrElse(t.identity, Seq.empty).map(f => ((t.identity: TypeSymbol, f), f :: Nil)).toSeq
        logger.debug("Mappings are: "+mappings)
        Some((t, mappings))
      case p @ Pure(StructNode(defs), ts) =>
        logger.debug("Creating source from Pure:", p)
        val mappings = defs.map { case (f, _) => ((ts, f), f :: Nil) }
        logger.debug("Mappings are: "+mappings)
        Some((p, mappings))
      case j @ Join(ls, rs, l1, r1, jt, on1) =>
        logger.debug("Creating source from Join:", j)
        val (l2 @ (_ :@ CollectionType(_, ltpe)), lmap) = dealias(l1)(createSourceOrTopLevel)
        val (r2 @ (_ :@ CollectionType(_, rtpe)), rmap) = dealias(r1)(createSourceOrTopLevel)
        // Detect and remove empty join sides
        val noCondition = on1 == LiteralNode(true)
        val noLeft = l2 match {
          case Pure(StructNode(Seq()), _) => true
          case _ => false
        }
        val noRight = r2 match {
          case Pure(StructNode(Seq()), _) => true
          case _ => false
        }
        if(noLeft && noCondition) {
          Some((r2, rmap))
        } else if(noRight && noCondition) {
          Some((l2, lmap))
        } else {
          val mappings =
            lmap.map { case (key, ss) => (key, ElementSymbol(1) :: ss )} ++
            rmap.map { case (key, ss) => (key, ElementSymbol(2) :: ss )}
          val mappingsM = mappings.toMap
          logger.debug("Mappings for `on` clause: "+mappingsM)
          val on2 = on1.replace({
            case p @ FwdPathOnTypeSymbol(ts, _ :: s :: Nil) =>
              //logger.debug(s"Finding ($ts, $s)")
              mappingsM.get((ts, s)) match {
                case Some(ElementSymbol(idx) :: ss) =>
                  //logger.debug(s"Found $idx :: $ss")
                  FwdPath((if(idx == 1) ls else rs) :: ss)
                case _ => p
              }
          }, bottomUp = true).nodeWithComputedType(typeChildren = true,
              scope = SymbolScope.empty + (j.leftGen -> l2.nodeType.asCollectionType.elementType) +
                (j.rightGen -> r2.nodeType.asCollectionType.elementType))
          logger.debug("Transformed `on` clause:", on2)
          val j2 = j.copy(left = l2, right = r2, on = on2).nodeWithComputedType()
          logger.debug("Created source from Join:", j2)
          Some((j2, mappings))
        }
      case n => None
    }

    /** Create a source node, or alternatively a top-level node (possibly lifting the node into a
      * subquery) if it is not a valid source. */
    def createSourceOrTopLevel(n: Node): (Node, Mappings) = createSource(n).getOrElse {
      logger.debug("Creating subquery from top-level:", n)
      createTopLevel(n)
    }

    /** Create a Union or Comprehension (suitable for the top level of a query). */
    def createTopLevel(n: Node): (Node, Mappings) = n match {
      case u @ Union(l1, r1, all, ls, rs) =>
        logger.debug("Converting Union:", Ellipsis(u, List(0), List(1)))
        val (l2, rep1) = createTopLevel(l1)
        val (r2, rep2) = createTopLevel(r1)
        // Assign LHS symbols to RHS
        val CollectionType(_, NominalType(lts, StructType(els))) = l2.nodeType
        def assign(n: Node): Node = n match {
          case u: Union =>
            u.nodeMapChildren(assign).nodeWithComputedType()
          case c: Comprehension =>
            val Some(Pure(StructNode(defs1), _)) = c.select
            val defs2 = defs1.zip(els).map { case ((_, n), (s, _)) => (s, n) }
            c.copy(select = Some(Pure(StructNode(defs2), lts))).nodeWithComputedType()
        }
        val u2 = u.copy(left = l2, right = assign(r2)).nodeWithComputedType()
        logger.debug("Converted Union:", u2)
        (u2, rep1)

      case n =>
        val (c, rep) = mergeTakeDrop(n, false)
        val mappings = rep.mapValues(_ :: Nil).toSeq
        logger.debug("Mappings are: "+mappings)
        (c, mappings)
    }

    def convert1(n: Node): Node = n match {
      case n :@ Type.Structural(CollectionType(cons, el)) =>
        convertOnlyInScalar(createTopLevel(n)._1)
      case a: Aggregate =>
        logger.debug("Merging Aggregate into Comprehension:", Ellipsis(a, List(0)))
        val (c1, rep) = mergeFilterWhere(a.from, true)
        val sel2 = applyReplacements(a.select, rep, c1)
        val c2 = c1.copy(select = Some(Pure(sel2))).nodeWithComputedType()
        val c3 = convertOnlyInScalar(c2)
        val res = Library.SilentCast.typed(a.nodeType, c3)
        logger.debug("Merged Aggregate into Comprehension as:", res)
        res
      case n =>
        n.nodeMapChildren(convert1, keepType = true)
    }

    def convertOnlyInScalar(n: Node): Node = n match {
      case n :@ Type.Structural(CollectionType(_, _)) =>
        n.nodeMapChildren(convertOnlyInScalar, keepType = true)
      case n => convert1(n)
    }

    convert1(tree)
  }

  /** Merge the common operations Bind, Filter and CollectionBase into an existing Comprehension.
    * This method is used at different stages of the pipeline. */
  def mergeCommon(rec: (Node, Boolean) => (Comprehension, Replacements), parent: (Node, Boolean) => (Comprehension, Replacements),
                  n: Node, buildBase: Boolean,
                  allowFilter: Boolean = true): (Comprehension, Replacements) = n match {
    case Bind(s1, f1, Pure(StructNode(defs1), ts1)) if !f1.isInstanceOf[GroupBy] =>
      val (c1, replacements1) = rec(f1, true)
      logger.debug("Merging Bind into Comprehension as 'select':", Ellipsis(n, List(0)))
      val defs2 = defs1.map { case (s, d) => (s, applyReplacements(d, replacements1, c1)) }
      val c2 = c1.copy(select = Some(Pure(StructNode(defs2), ts1))).nodeWithComputedType()
      logger.debug("Merged Bind into Comprehension as 'select':", c2)
      val replacements = defs2.map { case (f, _) => (ts1, f) -> f }.toMap
      logger.debug("Replacements are: "+replacements)
      (c2, replacements)

    case Filter(s1, f1, p1) if allowFilter =>
      val (c1, replacements1) = rec(f1, true)
      logger.debug("Merging Filter into Comprehension:", Ellipsis(n, List(0)))
      val p2 = applyReplacements(p1, replacements1, c1)
      val c2 =
        if(c1.groupBy.isEmpty) c1.copy(where = p2 +: c1.where) :@ c1.nodeType
        else c1.copy(having = p2 +: c1.having) :@ c1.nodeType
      logger.debug("Merged Filter into Comprehension:", c2)
      (c2, replacements1)

    case CollectionCast(ch, _) =>
      rec(ch, buildBase)

    case n => parent(n, buildBase)
  }

  /** Remove purely aliasing `Bind` mappings, apply the conversion to the source, then inject the
    * mappings back into the source's mappings. */
  def dealias(n: Node)(f: Node => (Node, Mappings)): (Node, Mappings) = {
    def isAliasing(base: Symbol, defs: IndexedSeq[(Symbol, Node)]) = {
      val r = defs.forall {
        case (_, FwdPath(s :: _)) if s == base => true
        case _ => false
      }
      logger.debug("Bind from "+base+" is aliasing: "+r)
      r
    }
    n match {
      case Bind(_, _: GroupBy, _) => f(n) // we need this Bind for the GroupBy transformation
      case Bind(s, from, Pure(StructNode(defs), ts1)) if isAliasing(s, defs) =>
        val (n2, map1) = dealias(from)(f)
        logger.debug("Recombining aliasing Bind mappings "+defs)
        val map1M = map1.toMap
        val map2 = defs.map { case (f1, p) =>
          val sel = p.findNode {
            case Select(_ :@ NominalType(_, _), _) => true
            case _ => false
          }.getOrElse(throw new SlickTreeException("Missing path on top of TypeSymbol in:", p))
          val Select(_ :@ NominalType(ts2, _), f2) = sel
          (ts1, f1) -> map1M((ts2, f2))
        }
        (n2, map2)
      case n => f(n)
    }
  }

  /** Apply the replacements and current selection of a Comprehension to a new Node that
    * will be merged into the Comprehension. */
  def applyReplacements(n1: Node, r: Replacements, c: Comprehension): Node = {
    val Some(Pure(StructNode(base), _)) = c.select
    val baseM = base.toMap
    n1.replace({ case n @ Select(_ :@ NominalType(ts, _), s) =>
      r.get((ts, s)) match {
        case Some(s2) => baseM(s2)
        case None => n
      }
    }, bottomUp = true, keepType = true)
  }

  object FwdPathOnTypeSymbol {
    def unapply(n: Node): Option[(TypeSymbol, List[Symbol])] = n match {
      case Ref(s) :@ NominalType(ts, _) => Some((ts, List(s)))
      case Select(_, s) :@ NominalType(ts, _) => Some((ts, List(s)))
      case Select(in, s) => unapply(in).map { case (ts, l) => (ts, l :+ s) }
      case _ => None
    }
  }
}
