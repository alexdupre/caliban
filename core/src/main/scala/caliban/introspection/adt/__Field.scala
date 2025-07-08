package caliban.introspection.adt

import caliban.Scala3Annotations.threadUnsafe
import caliban.Value.StringValue
import caliban.parsing.adt.Definition.TypeSystemDefinition.TypeDefinition.{ FieldDefinition, InputValueDefinition }
import caliban.parsing.adt.Directive
import caliban.schema.Annotations.GQLExcluded

import scala.util.hashing.MurmurHash3

case class __Field(
  name: String,
  description: Option[String],
  args: __DeprecatedArgs => List[__InputValue],
  `type`: () => __Type,
  isDeprecated: Boolean = false,
  deprecationReason: Option[String] = None,
  @GQLExcluded directives: Option[List[Directive]] = None
) {
  @transient @threadUnsafe
  final override lazy val hashCode: Int = MurmurHash3.productHash(this)

  def toFieldDefinition: FieldDefinition = {
    val allDirectives = (if (isDeprecated)
                           List(
                             Directive(
                               "deprecated",
                               List(deprecationReason.map(reason => "reason" -> StringValue(reason))).flatten.toMap
                             )
                           )
                         else Nil) ++ directives.getOrElse(Nil)
    FieldDefinition(description, name, allArgs.map(_.toInputValueDefinition), _type.toType(), allDirectives)
  }

  def toInputValueDefinition: InputValueDefinition =
    InputValueDefinition(description, name, _type.toType(), None, directives.getOrElse(Nil))

  lazy val allArgs: List[__InputValue] =
    args(__DeprecatedArgs.include)

  private[caliban] lazy val _type: __Type = `type`()

  private[caliban] lazy val allArgNames: Set[String] =
    allArgs.view.map(_.name).toSet
}
