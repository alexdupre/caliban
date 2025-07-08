package caliban.parsing.adt

import caliban.InputValue
import caliban.parsing.adt.Type.NamedType

import scala.util.hashing.MurmurHash3

sealed trait Selection extends Serializable {
  // TODO: Kept for binary compatibility
  @transient override lazy val hashCode: Int = super.hashCode()
}

object Selection {

  case class Field(
    alias: Option[String],
    name: String,
    arguments: Map[String, InputValue],
    directives: List[Directive],
    selectionSet: List[Selection],
    index: Int
  ) extends Selection {
    @transient final override lazy val hashCode: Int = MurmurHash3.productHash(this)
  }

  case class FragmentSpread(name: String, directives: List[Directive]) extends Selection {
    @transient final override lazy val hashCode: Int = MurmurHash3.productHash(this)
  }

  case class InlineFragment(
    typeCondition: Option[NamedType],
    dirs: List[Directive],
    selectionSet: List[Selection]
  ) extends Selection {
    @transient final override lazy val hashCode: Int = MurmurHash3.productHash(this)
  }

}
