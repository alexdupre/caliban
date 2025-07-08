package caliban.parsing.adt

import caliban.InputValue
import caliban.Scala3Annotations.threadUnsafe
import caliban.parsing.adt.Type.NamedType

import scala.util.hashing.MurmurHash3

sealed trait Selection extends Product with Serializable {

  @transient @threadUnsafe
  override final lazy val hashCode: Int = MurmurHash3.productHash(this)
}

object Selection {

  case class Field(
    alias: Option[String],
    name: String,
    arguments: Map[String, InputValue],
    directives: List[Directive],
    selectionSet: List[Selection],
    index: Int
  ) extends Selection

  case class FragmentSpread(name: String, directives: List[Directive]) extends Selection

  case class InlineFragment(
    typeCondition: Option[NamedType],
    dirs: List[Directive],
    selectionSet: List[Selection]
  ) extends Selection

}
