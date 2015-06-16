package slick.compiler

import slick.ast._
import Util._
import TypeUtil._

/** Remove unreferenced fields from StructNodes. */
class PruneProjections extends Phase {
  val name = "pruneProjections"

  def apply(state: CompilerState) = state.map { n => ClientSideOp.mapServerSide(n, true) { n =>
    val unionEquiv = n.collectAll[((TypeSymbol, Symbol), (TypeSymbol, Symbol))] {
      case Union(Bind(_, _, Pure(StructNode(defs1), ts1)), Bind(_, _, Pure(StructNode(defs2), ts2)), _, _, _) =>
        defs1.zip(defs2).map { case ((f1, _), (f2, _)) => ((ts1, f1), (ts2, f2)) }
    }.toMap
    logger.debug("Union equivalences: "+unionEquiv.mkString(","))
    val simpleReferenced = n.collect[(TypeSymbol, Symbol)] { case Select(_ :@ NominalType(s, _), f) => (s, f) }.toSet
    val referenced = simpleReferenced ++ simpleReferenced.flatMap(unionEquiv.get _)
    val allTSyms = n.collect[TypeSymbol] { case Pure(_, _) :@ CollectionType(_, NominalType(ts, _)) => ts }.toSet
    val unrefTSyms = allTSyms -- referenced.map(_._1)
    logger.debug(s"Unreferenced: ${unrefTSyms.mkString(", ")}; Field refs: ${referenced.mkString(", ")}")
    def tr(n: Node): Node =  n.replace {
      case (p @ Pure(s @ StructNode(ch), pts)) :@ CollectionType(_, NominalType(ts, _)) =>
        val ch2 = ch.collect { case (sym, n) if unrefTSyms.contains(ts) || referenced.contains((ts, sym)) => (sym, tr(n)) }
        if(ch2 == ch) p else Pure(StructNode(ch2), pts)
    }
    tr(n).nodeWithComputedType()
  }}
}

/** Convert unreferenced StructNodes to single columns or ProductNodes (which is needed for
  * aggregation functions and at the top level). */
class RemoveFieldNames extends Phase {
  val name = "removeFieldNames"

  def apply(state: CompilerState) = state.map { n => ClientSideOp.mapServerSide(n, true) { n =>
    val top = n.nodeType match {
      case CollectionType(_, NominalType(sym, _)) => sym
      case NominalType(sym, _) => sym
    }
    val refTSyms = n.collect[TypeSymbol] { case Select(_ :@ NominalType(s, _), _) => s }.toSet
    val allTSyms = n.collect[TypeSymbol] { case Pure(_, _) :@ CollectionType(_, NominalType(ts, _)) => ts }.toSet
    val unrefTSyms = allTSyms -- refTSyms
    def tr(n: Node): Node =  n.replace {
      case (p @ Pure(s: StructNode, pts)) :@ CollectionType(_, NominalType(ts, _)) if unrefTSyms contains ts =>
        val ch2 = s.nodeChildren.map(tr)
        Pure(if(ch2.length == 1 && ts != top) ch2(0) else ProductNode(ch2), pts)
    }
    tr(n)
  }}
}

