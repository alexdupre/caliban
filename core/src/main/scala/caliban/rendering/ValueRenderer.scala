package caliban.rendering

import caliban.Value.FloatValue.{ BigDecimalNumber, DoubleNumber, FloatNumber }
import caliban.Value.IntValue.{ BigIntNumber, IntNumber, LongNumber }
import caliban.Value.StringValue
import caliban.{ InputValue, ResponseValue, Value }

import scala.annotation.switch

object ValueRenderer {

  lazy val inputValueRenderer: Renderer[InputValue] = new Renderer[InputValue] {
    override protected[caliban] def unsafeRender(value: InputValue, indent: Option[Int], write: StringBuilder): Unit =
      value match {
        case in: InputValue.ListValue       => inputListValueRenderer.unsafeRender(in, indent, write)
        case in: InputValue.ObjectValue     => inputObjectValueRenderer.unsafeRender(in, indent, write)
        case InputValue.VariableValue(name) =>
          write += '$'
          write ++= name
        case StringValue(str)               =>
          write += '"'
          Renderer.escapedString.unsafeRender(str, indent, write)
          write += '"'
        case Value.EnumValue(value)         => Renderer.escapedString.unsafeRender(value, indent, write)
        case Value.BooleanValue(value)      => write append value
        case Value.NullValue                => write ++= "null"
        case IntNumber(value)               => write append value
        case LongNumber(value)              => write append value
        case BigIntNumber(value)            => write append value
        case FloatNumber(value)             => write append value
        case DoubleNumber(value)            => write append value
        case BigDecimalNumber(value)        => write append value
      }
  }

  lazy val inputObjectValueRenderer: Renderer[InputValue.ObjectValue] =
    Renderer.char('{') ++ Renderer
      .map(
        Renderer.string,
        inputValueRenderer,
        Renderer.char(',') ++ Renderer.spaceOrEmpty,
        Renderer.char(':') ++ Renderer.spaceOrEmpty
      )
      .contramap[InputValue.ObjectValue](_.fields) ++ Renderer.char('}')

  lazy val inputListValueRenderer: Renderer[InputValue.ListValue] =
    Renderer.char('[') ++ inputValueRenderer
      .list(Renderer.char(',') ++ Renderer.spaceOrEmpty)
      .contramap[InputValue.ListValue](_.values) ++ Renderer.char(']')

  lazy val enumInputValueRenderer: Renderer[Value.EnumValue] = new Renderer[Value.EnumValue] {
    override protected[caliban] def unsafeRender(
      value: Value.EnumValue,
      indent: Option[Int],
      write: StringBuilder
    ): Unit =
      Renderer.escapedString.unsafeRender(value.value, indent, write)
  }

  lazy val responseValueRenderer: Renderer[ResponseValue] = new Renderer[ResponseValue] {
    override protected[caliban] def unsafeRender(
      value: ResponseValue,
      indent: Option[Int],
      write: StringBuilder
    ): Unit =
      value match {
        case ResponseValue.ListValue(values) =>
          responseListValueRenderer.unsafeRender(ResponseValue.ListValue(values), indent, write)
        case in: ResponseValue.ObjectValue   =>
          responseObjectValueRenderer.unsafeRender(in, indent, write)
        case StringValue(str)                =>
          write += '"'
          Renderer.escapedString.unsafeRender(str, indent, write)
          write += '"'
        case Value.EnumValue(value)          =>
          write += '"'
          Renderer.escapedString.unsafeRender(value, indent, write)
          write += '"'
        case Value.BooleanValue(value)       => write append value
        case Value.NullValue                 => write append "null"
        case IntNumber(value)                => write append value
        case LongNumber(value)               => write append value
        case FloatNumber(value)              => write append value
        case DoubleNumber(value)             => write append value
        case BigDecimalNumber(value)         => write append value
        case BigIntNumber(value)             => write append value
        case ResponseValue.StreamValue(_)    => write append "<stream>"
      }
  }

  lazy val responseListValueRenderer: Renderer[ResponseValue.ListValue] =
    Renderer.char('[') ++ responseValueRenderer
      .list(Renderer.char(',') ++ Renderer.spaceOrEmpty)
      .contramap[ResponseValue.ListValue](_.values) ++ Renderer.char(']')

  lazy val responseObjectValueRenderer: Renderer[ResponseValue.ObjectValue] = new Renderer[ResponseValue.ObjectValue] {
    override protected[caliban] def unsafeRender(
      value: ResponseValue.ObjectValue,
      indent: Option[Int],
      write: StringBuilder
    ): Unit = {
      write += '{'
      var first = true
      value.fields.foreach { field =>
        if (first) first = false
        else {
          write += ','
          if (indent.nonEmpty) write += ' '
        }
        write += '"'
        Renderer.escapedString.unsafeRender(field._1, indent, write)
        write += '"'
        write += ':'
        if (indent.nonEmpty) write += ' '
        responseValueRenderer.unsafeRender(field._2, indent, write)
      }
      write += '}'
    }
  }

}
