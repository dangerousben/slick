package slick.compiler

import slick.ast.Library.AggregateFunctionSymbol
import slick.ast.TypeUtil._
import slick.ast.Util._
import slick.ast._
import slick.util.{Ellipsis, ??}

/** Rewrite aggregation function calls to Aggregate nodes. */
class CreateAggregates extends Phase {
  val name = "createAggregates"

  def apply(state: CompilerState) = state.map(tr)

  def tr(n: Node): Node = n.nodeMapChildren(tr, keepType = true) match {
    case n @ Apply(f: AggregateFunctionSymbol, Seq(from)) :@ tpe =>
      logger.debug("Converting aggregation function application", n)
      val CollectionType(_, elType @ Type.Structural(StructType(els))) = from.nodeType
      val s = new AnonSymbol
      val ref = Select(Ref(s) :@ elType, els.head._1) :@ els.head._2
      val a = Aggregate(s, from, Apply(f, Seq(ref))(tpe)).nodeWithComputedType()
      logger.debug("Converted aggregation function application", a)
      inlineMap(a)

    case n @ Bind(s1, from1, Pure(sel1, ts1)) if !from1.isInstanceOf[GroupBy] =>
      val (sel2, temp) = liftAggregates(sel1, s1)
      if(temp.isEmpty) n else {
        logger.debug("Lifting aggregates into join in:", n)
        logger.debug("New mapping with temporary refs:", sel2)
        val sources = (from1 match {
          case Pure(StructNode(Seq()), _) => Vector.empty[(Symbol, Node)]
          case _ => Vector(s1 -> from1)
        }) ++ temp.map { case (s, n) => (s, Pure(n)) }
        val from2 = sources.init.foldRight(sources.last._2) {
          case ((_, n), z) => Join(new AnonSymbol, new AnonSymbol, n, z, JoinType.Inner, LiteralNode(true))
        }.nodeWithComputedType()
        logger.debug("New 'from' with joined aggregates:", from2)
        val repl: Map[Symbol, List[Symbol]] = sources match {
          case Vector((s, n)) => Map(s -> List(s1))
          case _ =>
            val len = sources.length
            // Join(1, Join(2, Join(3, Join(4, 5))))
            // 1 -> s1._1
            // 2 -> s1._2._1
            // 3 -> s1._2._2._1
            // 4 -> s1._2._2._2._1
            // 5 -> s1._2._2._2._2
            val it = Iterator.iterate(s1)(_ => ElementSymbol(2))
            sources.zipWithIndex.map { case ((s, _), i) =>
              val l = List.iterate(s1, i+1)(_ => ElementSymbol(2))
              s -> (if(i == len-1) l else l :+ ElementSymbol(1))
            }.toMap
        }
        logger.debug("Replacement paths: " + repl)
        val scope = SymbolScope.empty + (s1, from2.nodeType.asCollectionType.elementType)
        val replNodes = repl.mapValues(ss => FwdPath(ss).nodeWithComputedType(scope))
        logger.debug("Replacement path nodes: ", StructNode(replNodes.toIndexedSeq))
        val sel3 = sel2.replace({ case n @ Ref(s) => replNodes.getOrElse(s, n) }, keepType = true)
        val n2 = Bind(s1, from2, Pure(sel3, ts1)).nodeWithComputedType()
        logger.debug("Lifted aggregates into join in:", n2)
        n2
      }

    //FilteredQuery (Filter, SortBy, Take, Drop) -- GroupBy, Join, Union, Bind
    case n => n
  }

  /** Recursively inline mapping Bind calls under an Aggregate */
  def inlineMap(a: Aggregate): Aggregate = a.from match {
    case Bind(s1, f1, Pure(StructNode(defs1), ts1)) =>
      logger.debug("Inlining mapping Bind under Aggregate", a)
      val defs1M = defs1.toMap
      val sel = a.select.replace({
        case FwdPath(s :: f :: rest) if s == a.sym =>
          rest.foldLeft(defs1M(f)) { case (n, s) => n.select(s) }.nodeWithComputedType()
      }, keepType = true)
      val a2 = Aggregate(s1, f1, sel) :@ a.nodeType
      logger.debug("Inlining mapping Bind under Aggregate", a2)
      inlineMap(a2)
    case _ => a
  }

  /** Find all scalar Aggregate calls in a sub-tree that do not refer to the given Symbol,
    * and replace them by temporary Refs. */
  def liftAggregates(n: Node, outer: Symbol): (Node, Map[Symbol, Aggregate]) = n match {
    case a @ Aggregate(s1, f1, sel1) =>
      if(a.findNode {
          case Ref(s) => s == outer
          case Select(_, s) => s == outer
          case _ => false
        }.isDefined) (a, Map.empty)
      else {
        val s, f = new AnonSymbol
        val a2 = Aggregate(s1, f1, StructNode(IndexedSeq(f -> sel1))).nodeWithComputedType()
        (Select(Ref(s) :@ a2.nodeType, f).nodeWithComputedType(), Map(s -> a2))
      }
    case n :@ CollectionType(_, _) =>
      (n, Map.empty)
    case n =>
      val mapped = n.nodeChildren.map(liftAggregates(_, outer))
      val m = mapped.flatMap(_._2).toMap
      val n2 =
        if(m.isEmpty) n else n.nodeRebuildOrThis(mapped.map(_._1).toIndexedSeq) :@ n.nodeType
      (n2, m)
  }
}
