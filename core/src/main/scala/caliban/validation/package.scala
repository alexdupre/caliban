package caliban

import caliban.Scala3Annotations.threadUnsafe
import caliban.parsing.adt.Definition.ExecutableDefinition.{ FragmentDefinition, OperationDefinition }
import caliban.introspection.adt.{ __Field, __Type }
import caliban.parsing.SourceMapper
import caliban.parsing.adt.{ Document, Selection, VariableDefinition }
import caliban.parsing.adt.Selection.Field
import caliban.schema.{ RootType, Types }

import scala.util.hashing.MurmurHash3

package object validation {
  case class SelectedField(
    parentType: __Type,
    selection: Field,
    fieldDef: __Field
  ) {
    @transient @threadUnsafe
    final override lazy val hashCode: Int = MurmurHash3.productHash(this)
  }

  type FieldMap = Map[String, Set[SelectedField]]

  case class Context(
    document: Document,
    rootType: RootType,
    operations: List[OperationDefinition],
    fragments: Map[String, FragmentDefinition],
    selectionSets: List[Selection],
    variables: Map[String, InputValue]
  ) {
    lazy val variableDefinitions: Map[String, VariableDefinition] =
      operations.flatMap(_.variableDefinitions.map(d => d.name -> d)).toMap
  }

  object Context {
    val empty: Context =
      Context(Document(Nil, SourceMapper.empty), RootType(Types.boolean, None, None), Nil, Map.empty, Nil, Map.empty)
  }
}
