# Schema generation

A GraphQL schema will be derived automatically at compile-time (no reflection) from the types present in your resolver.

If you're more interested in the schema-first approach, it is also possible to [generate the Scala code from a GraphQL schema file](server-codegen.md).

The table below shows how common Scala types are converted to GraphQL types.

| Scala Type                                                          | GraphQL Type                                                     |
|---------------------------------------------------------------------|------------------------------------------------------------------|
| Boolean                                                             | Boolean                                                          |
| Int                                                                 | Int                                                              |
| Float                                                               | Float                                                            |
| Double                                                              | Float                                                            |
| String                                                              | String                                                           |
| java.util.UUID                                                      | ID                                                               |
| Unit                                                                | Unit (custom scalar)                                             |
| Long                                                                | Long (custom scalar)                                             |
| BigInt                                                              | BigInt (custom scalar)                                           |
| BigDecimal                                                          | BigDecimal (custom scalar)                                       |
| java.time.Instant                                                   | Instant (custom scalar)                                          |
| java.time.LocalDate                                                 | LocalDate (custom scalar)                                        |
| java.time.LocalTime                                                 | LocalTime (custom scalar)                                        |
| java.time.LocalDateTime                                             | LocalDateTime (custom scalar)                                    |
| java.time.OffsetDateTime                                            | OffsetDateTime (custom scalar)                                   |
| java.time.ZonedDateTime                                             | ZonedDateTime (custom scalar)                                    |
| Case Class                                                          | Object                                                           |
| Sealed Trait                                                        | Enum, Union or Interface (see [below](#enums-unions-interfaces)) |
| Option[A]                                                           | Nullable A                                                       |
| List[A]                                                             | List of A                                                        |
| Set[A]                                                              | List of A                                                        |
| Seq[A]                                                              | List of A                                                        |
| Vector[A]                                                           | List of A                                                        |
| A => B                                                              | A and B                                                          |
| (A, B)                                                              | Object with 2 fields `_1` and `_2`                               |
| Either[A, B]                                                        | Object with 2 nullable fields `left` and `right`                 |
| Map[A, B]                                                           | List of Object with 2 fields `key` and `value`                   |
| ZIO[R, Nothing, A]                                                  | A                                                                |
| ZIO[R, Throwable, A]                                                | Nullable A                                                       |
| Future[A]                                                           | Nullable A                                                       |
| ZStream[R, Throwable, A]                                            | A (subscription) or List of A (query, mutation)                  |
| Json (from [Circe](https://github.com/circe/circe))                 | Json (custom scalar, need `import caliban.interop.circe.json._`) |
| Json (from [play-json](https://github.com/playframework/play-json)) | Json (custom scalar, need `import caliban.interop.play.json._`)  |

See the [Custom Types](#custom-types) section to find out how to support your own types.

If you want Caliban to support other standard types, feel free to [file an issue](https://github.com/ghostdogpr/caliban/issues) or even a PR.

## Enums, unions, interfaces

A sealed trait will be converted to a different GraphQL type depending on its content:

- a sealed trait with only case objects will be converted to an `ENUM`
- a sealed trait with only case classes will be converted to a `UNION`

GraphQL does not support empty objects, so in case a sealed trait mixes case classes and case objects, a union type will be created and the case objects will have a "fake" field named `_` which is not queryable.

```scala mdoc:silent
sealed trait Origin
object Origin {
  case object EARTH extends Origin
  case object MARS  extends Origin
  case object BELT  extends Origin
}
```

The snippet above will produce the following GraphQL type:

```graphql
enum Origin {
  BELT
  EARTH
  MARS
}
```

Here's an example of union:

```scala
sealed trait Role
object Role {
  case class Captain(shipName: String) extends Role
  case class Engineer(specialty: String) extends Role
  case object Mechanic extends Role
}
```

The snippet above will produce the following GraphQL type:

```graphql
union Role = Captain | Engineer | Mechanic

type Captain {
  shipName: String!
}

type Engineer {
  specialty: String!
}

type Mechanic {
  _: Boolean!
}
```

If your type needs to be shared between multiple unions you can use the `@GQLValueType` annotation to have caliban
proxy to another type beyond the sealed trait.

```scala
case class Pilot(callSign: String)

sealed trait Role
object Role {
  case class Captain(shipName: String) extends Role
  case class Engineer(specialty: String) extends Role
  @GQLValueType
  case class Proxy(pilot: Pilot) extends Role
}
```

This will produce the following GraphQL Types:

```graphql
union Role = Captain | Engineer | Pilot

type Captain {
  shipName: String!
}

type Engineer {
  specialty: String!
}

type Pilot {
  callSign: String!
}
```

If you prefer an `Interface` instead of a `Union` type, add the `@GQLInterface` annotation to your sealed trait.
An interface will be created with all the fields that are common to the case classes extending the sealed trait, as long as they return the same type.

If you prefer to have a `Union` type instead of an `Enum`, even when the sealed trait contains only objects, add the `@GQLUnion` annotation.

## Case classes and sealed traits

The transformation between Scala types and GraphQL types is handled by a typeclass named `Schema`. As mentioned earlier, Caliban provides instances of `Schema` for all basic Scala types, but inevitably you will need to support your own types, in particular case classes and sealed traits.

Caliban is able to generate instances of `Schema` for case classes and sealed traits. You have two choices for doing that: auto derivation and semi-auto derivation.

### Auto derivation
Auto derivation is achieved easily by adding the following import:
```scala mdoc:silent
import caliban.schema.Schema.auto._
```
Using this import, Caliban will generate `Schema` instances for all the case classes and sealed traits that are found inside your resolver.

::: warning Limitations
Auto derivation is the easiest way to get started, but it has some drawbacks:
- If a type is referenced in several places inside your resolver, a `Schema` will be generated for each occurrence, which can lead to longer compilation times and a high amount of generated code (a sign of this is that the compiler will suggest increasing `-Xmax-inlines` in Scala 3).
- When a `Schema` is missing for a nested type inside your resolver, it can sometimes be difficult to find out which type is missing when using auto derivation, because the error message will mention the root type and not the nested one.
- The macro that generates the `Schema` instances sometimes gets confused when there are a lot of nested or recursive types, and can mistakenly generate a `Schema` for types that already have a `Schema` in scope. For this reason, semi-auto derivation is recommended for non-trivial schemas.
:::

### Semi-auto derivation
Semi-auto derivation is achieved as follows for each type that needs a `Schema` instance (`MyClass` in the example):

<code-group>
  <code-block title="Scala 2" active>

```scala mdoc:silent
import caliban.schema.Schema

case class MyClass(field: String)

implicit val schemaForMyClass: Schema[Any, MyClass] = Schema.gen
```
  </code-block>
  <code-block title="Scala 3">

```scala
import caliban.schema.Schema

case class MyClass(field: String) derives Schema.SemiAuto

// if you don't want to use the `derives` syntax, you can also use the following:
given Schema[Any, MyClass] = Schema.gen
```
  </code-block>
</code-group>

In Scala 3, derivation doesn't support value classes and opaque types. You can use `Schema.genDebug` to print the generated code in the console.

### Combining auto and semi-auto derivation

For some types such as enums, it might be desirable to use auto derivation to reduce boilerplate schema definitions:

<code-group>
  <code-block title="Scala 2" active>

```scala mdoc:silent:reset
import caliban.schema.Schema

sealed trait Origin
object Origin {
  case object EARTH extends Origin
  case object MARS  extends Origin
  case object BELT  extends Origin
}

implicit val schemaForMyClass: Schema[Any, Origin] = {
  import Schema.auto._
  Schema.gen
}
```
  </code-block>
  <code-block title="Scala 3 (Any schema)">

```scala
import caliban.schema.Schema

enum Origin derives Schema.Auto {
  case EARTH, MARS, BELT
}

// if you don't want to use the `derives` syntax, you can also use the following:
given Schema[Any, Origin] = Schema.Auto.derived
```
  </code-block>
  <code-block title="Scala 3 (Custom schema)">

```scala
import caliban.schema.SchemaDerivation

trait MyEnv
object EnvSchema extends SchemaDerivation[MyEnv]

enum Origin derives EnvSchema.Auto {
  case EARTH, MARS, BELT
}

// if you don't want to use the `derives` syntax:
given Schema[MyEnv, Origin] = EnvSchema.Auto.derived
```
  </code-block>
</code-group>

### Deriving fields from case class methods (Scala 3 only)

In certain cases, your type might contain fields whose value depends on other fields. For example, you might have a `Person` type with a `fullName` field that is derived from the `firstName` and `lastName` fields. In this case, you can use the `@GQLField` annotation to indicate that the field should be derived from the method with the same name.

```scala
import caliban.schema.Schema
import caliban.schema.Annotations.GQLField

case class Person(
  firstName: String,
  lastName: String
) derives Schema.SemiAuto {
  @GQLField def fullName: String = s"$firstName $lastName"
}
```

This case class will generate the following GraphQL type:

```graphql
type Person {
  firstName: String!
  lastName: String!
  fullName: String!
}
```

The methods annotated with `@GQLField` can return any type for which a `Schema` is defined for, including effects such as `ZIO` and `ZQuery`.
In addition, you can use any other annotation that is supported for case class arguments, such as `@GQLName`, `@GQLDescription` and `@GQLDeprecated`.

To reduce boilerplate of annotating a lot of methods with `@GQLField`, Caliban also provides the `@GQLFieldsFromMethods` annotation that can be used to derive fields from all methods in a case class / case object.

For demonstration purposes (only!), the example above can be rewritten as follows:

```scala 3
import caliban.schema.Annotations.GQLFieldsFromMethods

@GQLFieldsFromMethods
case class Person(
  fullName: String
) derives Schema.SemiAuto {
  private val split = fullName.split(" ")
  
  def firstName: String = split.head
  def lastName: String  = split.last
}
```

::: tip
Annotate a public method with `@GQLExcluded` to exclude it from field derivation.
:::

::: warning Caveats
Derivation of fields via the `@GQLField` / `@GQLFieldsFromMethods` annotation can be convenient in certain cases, but has the following limitations:
- The method cannot take arguments. If you need to derive a field that requires arguments, you can return a function instead.
- The method must be public (i.e. not `private` or `protected`).
- It currently only works with methods (i.e., `def`). If you need to cache the output of the method, you can create a private lazy val and return it from the method.
- It is not compatible with ahead-of-time compilation (e.g., generating a GraalVM native-image executable).
:::

## Arguments

To declare a field that take arguments, create a dedicated case class representing the arguments and make the field a _function_ from this class to the result type.

```scala mdoc:silent
case class Character(name: String, origin: Origin)
case class FilterArgs(origin: Option[Origin])
case class Queries(characters: FilterArgs => List[Character])
```

The snippet above will produce the following GraphQL type:

```graphql
type Queries {
  characters(origin: Origin): [Character!]!
}
```

Caliban provides auto-derivation for common types such as `Int`, `String`, `List`, `Option`, etc. but you can also support your own types by providing an implicit instance of `ArgBuilder` that defines how incoming arguments from that types should be extracted. You also need a `Schema` for those types.

Derivation of `ArgBuilder` for case classes works similarly to `Schema` derivation. You can use auto derivation by adding the following import:

```scala mdoc:silent
import caliban.schema.ArgBuilder.auto._
```

Or you can use semi-auto derivation as follows:

<code-group>
  <code-block title="Scala 2" active>

```scala mdoc:silent:reset
import caliban.schema.{ArgBuilder, Schema}

case class MyClass(field: String)

implicit val argBuilderForMyClass: ArgBuilder[MyClass] = ArgBuilder.gen
implicit val schemaForMyClass: Schema[Any, MyClass]    = Schema.gen
```
  </code-block>
  <code-block title="Scala 3">

```scala
import caliban.schema.{ArgBuilder, Schema}

case class MyClass(field: String) derives Schema.SemiAuto, ArgBuilder

// if you don't want to use the `derives` syntax, you can also use the following:
given ArgBuilder[MyClass]  = ArgBuilder.gen
given Schema[Any, MyClass] = Schema.gen
```
  </code-block>
</code-group>

::: tip
There is no `ArgBuilder` for tuples. If you have multiple arguments, use a case class containing all of them instead of a tuple.
:::

### Input objects

GraphQL input objects can be derived from case classes in the same way as arguments

```scala mdoc:silent:reset
case class Name(firstName: String, lastName: String)
case class NameArgs(name: Name)
case class Queries(author: NameArgs => String)
```

This will generate the following schema:

```graphql
input NameInput {
    firstName: String!
    lastName: String!
}

type Queries {
    author(name: NameInput!): String!
}
```

### `@oneOf` input objects

A `@oneOf` input object is a special type of input object, in which only one of its fields must be set by the client. It is especially useful when you want a user to be able to choose between several potential input types.
This feature is still an RFC and therefore not yet officially part of the GraphQL spec, but Caliban supports it!

To define a `@oneOf` input object, you need to create a sealed trait (or an enum in Scala 3) with case classes that extend it. The case classes must have a single field, which is the field that the client can set. The sealed trait / enum must be annotated with `@GQLOneOfInput`.

<code-group>
  <code-block title="Sealed trait" active>

```scala mdoc:silent:reset
import caliban.schema.Annotations.GQLOneOfInput

case class Name(firstName: String, lastName: String)

@GQLOneOfInput
sealed trait AuthorInput
object AuthorInput {
  case class ById(id: String)
  case class ByName(name: Name)
}

case class AuthorArgs(lookup: AuthorInput)
case class Queries(author: AuthorArgs => String)
```
  </code-block>
  <code-block title="Enum (Scala 3)">

```scala 3 mdoc:silent:reset
import caliban.schema.Annotations.GQLOneOfInput

case class Name(firstName: String, lastName: String)

@GQLOneOfInput
enum AuthorInput {
  case ById(id: String)
  case ByName(name: Name)
}

case class AuthorArgs(lookup: AuthorInput)
case class Queries(author: AuthorArgs => String)
```
  </code-block>
</code-group>

This will generate the following schema, and the validation will verify that only one of those fields is provided in incoming queries.

```graphql
input NameInput {
    firstName: String!
    lastName: String!
}

input AuthorInput @oneOf {
    id: String
    name: NameInput
}

type Queries {
    author(lookup: AuthorInput!): String!
}
```

A few things to keep in mind when using `@oneOf` input objects:
- The leaf case classes must contain **exactly 1 non-nullable field**. If you need more than one field, you should wrap them in a case class.
- The field names in the leaf cases must be unique.
- You must have a `Schema` and an `ArgBuilder` for any objects used in the leaf cases.

## Custom types

Caliban provides auto-derivation for common types such as `Int`, `String`, `List`, `Option`, etc. but you can also support your own types by providing an implicit instance of `Schema`. Note that you don't have to do this if your types are just case classes composed of common types.

An easy way to do this is to reuse existing instances and use `contramap` to map from your type to the original type. Here's an example of creating an instance for [refined](https://github.com/fthomas/refined)'s `NonEmptyString` reusing existing instance for `String` (if you use `refined`, you might want to look at [caliban-refined](https://github.com/niqdev/caliban-extras#caliban-refined)):

```scala
import caliban.schema._
implicit val nonEmptyStringSchema: Schema[Any, NonEmptyString] = 
  Schema.stringSchema.contramap(_.value)
```

You can also use the `scalarSchema` helper to create your own scalar types, providing a name, an optional description, and a function from your type to a `ResponseValue`:

```scala mdoc:silent:reset
import caliban.schema._
import caliban.ResponseValue.ObjectValue

implicit val unitSchema: Schema[Any, Unit] =
  Schema.scalarSchema("Unit", None, None, None, _ => ObjectValue(Nil))
```

If you are using a custom type as part of the input you also have to provide an implicit instance of `ArgBuilder`. For example here's how to do that for `java.time.LocalDate`:

```scala mdoc:silent
import java.time.LocalDate
import scala.util.Try

import caliban.Value
import caliban.CalibanError.ExecutionError
import caliban.schema.ArgBuilder

implicit val localDateArgBuilder: ArgBuilder[LocalDate] = {
  case Value.StringValue(value) =>
    Try(LocalDate.parse(value))
      .fold(ex => Left(ExecutionError(s"Can't parse $value into a LocalDate", innerThrowable = Some(ex))), Right(_))
  case other => Left(ExecutionError(s"Can't build a LocalDate from input $other"))
}
```

Value classes (`case class SomeWrapper(self: SomeType) extends AnyVal`) will be unwrapped by default in Scala 2 (this is not supported by Scala 3 derivation).

## Effects

Fields can return ZIO effects. This allows you to leverage all the features provided by ZIO: timeouts, retries, access to ZIO environment, memoizing, etc. An effect will be run every time a query requiring the corresponding field is executed.

```scala mdoc:silent:reset
import zio._

type CharacterName = String
case class Character(name: CharacterName)
case class Queries(characters: Task[List[Character]],
                   character: CharacterName => RIO[Console, Character])
```

If you don't use ZIO environment (`R` = `Any`), there is nothing special to do to get it working.

If you require a ZIO environment and use Scala 2, you can't use `Schema.gen` or the import we saw previously because they expect `R` to be `Any`. Instead, you need to make a new object that extends `caliban.schema.GenericSchema[R]` for your custom `R`. Then you can use `gen` or `auto` from that object to generate your schema.
```scala mdoc:silent
import caliban._
import caliban.schema._

type MyEnv = Console 

object customSchema extends GenericSchema[MyEnv]
import customSchema.auto._

// if you use semi-auto generation, use this instead:
// implicit val characterSchema: Schema[MyEnv, Character] = customSchema.gen
// implicit val queriesSchema: Schema[MyEnv, Queries] = customSchema.gen

val queries = Queries(ZIO.attempt(???), _ => ZIO.succeed(???))
val api = graphQL(RootResolver(queries))
```

If you require a ZIO environment and use Scala 3, things are simpler since you don't need `GenericSchema`. Just make sure to use `Schema.gen` with the proper R type parameter.
To make sure Caliban uses the proper environment, you need to specify it explicitly to `graphQL(...)`, unless you already have `Schema` instances for your root operations in scope.
```scala
val queries = Queries(ZIO.attempt(???), _ => ZIO.succeed(???))
val api = graphQL[MyEnv, Queries, Unit, Unit](RootResolver(queries))
// or
// implicit val queriesSchema: Schema[MyEnv, Queries] = Schema.gen
// val api = graphQL(RootResolver(queries)) // it will infer MyEnv thanks to the instance above
```

When using the `derives` syntax in Scala 3, you need to create an object extending `caliban.schema.SchemaDerivation[R]` and use the `SemiAuto` method to generate the schema.
```scala
object customSchema extends SchemaDerivation[MyEnv]
case class Queries(test: RIO[MyEnv, List[Int]]) derives customSchema.SemiAuto
```

## Subscriptions

All the fields of the subscription root case class MUST return `ZStream` or `? => ZStream` objects.

The [cats and monix interop modules](interop.md) also let you use fs2 `Stream` and monix `Observable` respectively.

## Annotations

Caliban supports a few annotations to enrich data types:

- `@GQLName("name")` allows you to specify a different name for a data type or a field.
- `@GQLInputName("name")` allows you to specify a different name for a data type used as an input (by default, the suffix `Input` is appended to the type name).
- `@GQLDescription("description")` lets you provide a description for a data type or field. This description will be visible when your schema is introspected.
- `@GQLDeprecated("reason")` allows deprecating a field or an enum value.
- `@GQLExcluded` allows you to hide a field from the generated schema.
- `@GQLInterface` to force a sealed trait generating an interface instead of a union.
- `@GQLDirective(directive: Directive)` to add a directive to a field or type.
- `@GQLValueType(isScalar)` forces a type to behave as a value type for derivation. Meaning that caliban will ignore the outer type and take the first case class parameter as the real type. If `isScalar` is true, it will generate a scalar named after the case class (default: false).
- `@GQLDefault("defaultValue")` allows you to specify a default value for an input field using GraphQL syntax. The default value will be visible in your schema's SDL and during introspection.
- `@GQLOneOfInput` allows you turn a sealed trait or Scala 3 enum into an `@oneOf` input type.

## Java 8 Time types

Caliban provides implicit `Schema` types for the standard `java.time` types, by default these will use the
ISO standard strings for serialization and deserialization. However, you can customize this behavior by using
explicit constructor available under the `ArgBuilder` companion object. For instance, you can specify an `instantEpoch` 
to handle instants which are encoded using a `Long` from the standard java epoch time (January 1st 1970 00:00:00).
For some time formats you can also specify a specific `DateTimeFormatter` to handle your particular date time needs.

## Using features that are disabled by default

Some features of Caliban's schema derivation are disabled by default.
To enable them, you need to declare a custom schema derivation object like this:

<code-group>
  <code-block title="Scala 2" active>

```scala
import caliban.schema.SchemaDerivation

object MySchemaDerivation extends SchemaDerivation[Any] {
  override def config = DerivationConfig(
    // add your config overrides here
    enableSemanticNonNull = true
  )
}

case class MyClass(field: String)

// use the custom schema derivation defined above
implicit val schemaForMyClass: Schema[Any, MyClass] = MySchemaDerivation.gen
```
  </code-block>
  <code-block title="Scala 3 (with given)">

```scala
import caliban.schema.SchemaDerivation

object MySchemaDerivation extends SchemaDerivation[Any] {
  override def config = DerivationConfig(
    // add your config overrides here
    enableSemanticNonNull = true
  )
}

case class MyClass(field: String)

// use the custom schema derivation defined above
given Schema[Any, MyClass] = MySchemaDerivation.gen
```
  </code-block>
  <code-block title="Scala 3 (with derives)">

```scala
import caliban.schema.{ CommonSchemaDerivation, Schema }

trait MySchemaDerivation[R] extends CommonSchemaDerivation {
  override def config = DerivationConfig(
    // add your config overrides here
    enableSemanticNonNull = true
  )

  final class SemiAuto[A](impl: Schema[R, A]) extends Schema[R, A] {
    export impl.*
  }

  object SemiAuto {
    inline def derived[A]: SemiAuto[A] = new SemiAuto[A](MySchemaDerivation.derived[R, A])
  }
}

object MySchemaDerivation extends MySchemaDerivation[Any]

case class MyClass(field: String) derives MySchemaDerivation.SemiAuto
```
  </code-block>
</code-group>

### SemanticNonNull support

Caliban supports deriving schemas to the form that supports [the SemanticNonNull type RFC](https://github.com/graphql/graphql-spec/pull/1065), by introducing the `@semanticNonNull` directive.
While Caliban resolves all fallible effectful types (`ZIO[R, Throwable, A]`, ...) as nullable by default,
with the feature enabled, fields that don't get resolved to nullable types (for example, `ZIO[R, Throwable, A]` where `A` is not `Option[A]`, ...)
will be marked with `@semanticNonNull` to express that the field never returns `null` unless the effect fails.
`@GQLNullable` annotation can be used to override this behavior per field.

If you have custom types that override the `Schema` trait, make sure to override `nullable` and `canFail` methods to return the correct values.
All types that return `false` for `nullable` and `true` for `canFail` will be treated as semantically non-nullable.

## Building Schemas by hand

Sometimes for whatever reason schema generation fails. This can happen if your schema has co-recursive types and derivation is unable
to generate a schema for them. In cases like these you may need to instead create your own schema by hand.

Consider the case where you have three types which create cyclical dependencies on one another

```scala mdoc:silent
import zio.UIO

case class Group(id: String, users: UIO[List[User]], parent: UIO[Option[Group]], organization: UIO[Organization])
case class Organization(id: String, groups: UIO[List[Group]])
case class User(id: String, group: UIO[Group])
```

These three types all depend on one another and if you attempt to generate a schema from them you will either end up with compiler errors or you will end up with a nasty runtime
error from a `NullPointerException`. To help the compiler out we can hand generate the types for these case classes instead.

<code-group>
  <code-block title="Scala 2" active>

```scala mdoc:silent
import caliban.schema.Schema
import caliban.schema.Schema.{obj, field}

implicit lazy val groupSchema: Schema[Any, Group] = obj("Group", Some("A group of users"))(
  implicit ft =>
    List(
      field("id")(_.id),
      field("users")(_.users),
      field("parent")(_.parent),
      field("organization")(_.organization)
    )
)
implicit lazy val orgSchema: Schema[Any, Organization] = obj("Organization", Some("An organization of groups"))(
  implicit ft =>
    List(
      field("id")(_.id),
      field("groups")(_.groups)
    )
)

implicit lazy val userSchema: Schema[Any, User] = obj("User", Some("A user of the service"))(
  implicit ft =>
    List(
      field("id")(_.id),
      field("group")(_.group)
    )
)
```
  </code-block>
  <code-block title="Scala 3">

```scala 3 mdoc:silent
import caliban.schema.Schema
import caliban.schema.Schema.{customObj, field}

given Schema[Any, Group] = customObj("Group", Some("A group of users"))(
  field("id")(_.id),
  field("users")(_.users),
  field("parent")(_.parent),
  field("organization")(_.organization)
)
given Schema[Any, Organization] = customObj("Organization", Some("An organization of groups"))(
  field("id")(_.id),
  field("groups")(_.groups)
)

given Schema[Any, User] = customObj("User", Some("A user of the service"))(
  field("id")(_.id),
  field("group")(_.group)
)
```
  </code-block>
</code-group>

## Schema transformations

It is also possible to modify your schemas after they have been generated.
This can be useful if you want to rename or remove particular types, fields or arguments from your schema without modifying the related Scala types.

For that, simply use the `GraphQL#transform` method and provide one of the possible transformers:
- `RenameType` to rename types (providing a list of `(OldName -> NewName)`)
- `RenameField` to rename a field (providing a list of `(TypeName -> oldName -> newName)`)
- `RenameArgument` to rename an argument (providing a list of `(TypeName -> fieldName -> oldArgumentName -> newArgumentName)`)
- `ExcludeField` to exclude a field (providing a list of `(TypeName -> fieldToBeExcluded)`)
- `ExcludeInputField` to exclude an input field (providing a list of `(TypeName -> fieldToBeExcluded)`)
- `ExcludeArgument` to exclude an argument (providing a list of `(TypeName -> fieldName -> argumentToBeExcluded)`)
- `ExcludeDirectives` to exclude fields and input fields annotated with a specific directive (providing a list of `GQLDirective` or a `Directive => Boolean` predicate)


In the following example, we can expose 2 different APIs created from the same schema: the v1 API will not expose the `nicknames` field of the `Character` type.
```scala
case class Beta() extends GQLDirective(Directive("beta"))
case class Queries(character: Character)

case class Character(
  name: String,
  @Beta nicknames: List[String]
)

val apiBeta = graphQL(RootResolver(Queries(???, ???)))
val apiV1   = apiBeta.transform(Transformer.ExcludeDirectives(Beta()))

// alternatively:
// val apiV1 = apiBeta.transform(Transformer.ExcludeField("Character" -> "nicknames"))
```

You can also create your own transformers by extending the `Transformer` trait and implementing its methods.
