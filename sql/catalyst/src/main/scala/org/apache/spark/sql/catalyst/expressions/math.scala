/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.{lang => jl}
import java.util.Arrays

import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.{TypeCheckSuccess, TypeCheckFailure}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * A leaf expression specifically for math constants. Math constants expect no input.
 * @param c The math constant.
 * @param name The short name of the function
 */
abstract class LeafMathExpression(c: Double, name: String)
  extends LeafExpression with Serializable {
  self: Product =>

  override def dataType: DataType = DoubleType
  override def foldable: Boolean = true
  override def nullable: Boolean = false
  override def toString: String = s"$name()"

  override def eval(input: InternalRow): Any = c

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    s"""
      boolean ${ev.isNull} = false;
      ${ctx.javaType(dataType)} ${ev.primitive} = java.lang.Math.$name;
    """
  }
}

/**
 * A unary expression specifically for math functions. Math Functions expect a specific type of
 * input format, therefore these functions extend `ExpectsInputTypes`.
 * @param f The math function.
 * @param name The short name of the function
 */
abstract class UnaryMathExpression(f: Double => Double, name: String)
  extends UnaryExpression with Serializable with ImplicitCastInputTypes { self: Product =>

  override def inputTypes: Seq[DataType] = Seq(DoubleType)
  override def dataType: DataType = DoubleType
  override def nullable: Boolean = true
  override def toString: String = s"$name($child)"

  protected override def nullSafeEval(input: Any): Any = {
    val result = f(input.asInstanceOf[Double])
    if (result.isNaN) null else result
  }

  // name of function in java.lang.Math
  def funcName: String = name.toLowerCase

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        ${ev.primitive} = java.lang.Math.${funcName}($eval);
        if (Double.valueOf(${ev.primitive}).isNaN()) {
          ${ev.isNull} = true;
        }
      """
    })
  }
}

/**
 * A binary expression specifically for math functions that take two `Double`s as input and returns
 * a `Double`.
 * @param f The math function.
 * @param name The short name of the function
 */
abstract class BinaryMathExpression(f: (Double, Double) => Double, name: String)
  extends BinaryExpression with Serializable with ImplicitCastInputTypes { self: Product =>

  override def inputTypes: Seq[DataType] = Seq(DoubleType, DoubleType)

  override def toString: String = s"$name($left, $right)"

  override def dataType: DataType = DoubleType

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    val result = f(input1.asInstanceOf[Double], input2.asInstanceOf[Double])
    if (result.isNaN) null else result
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"java.lang.Math.${name.toLowerCase}($c1, $c2)")
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
// Leaf math functions
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////

case class EulerNumber() extends LeafMathExpression(math.E, "E")

case class Pi() extends LeafMathExpression(math.Pi, "PI")

////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
// Unary math functions
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////

case class Acos(child: Expression) extends UnaryMathExpression(math.acos, "ACOS")

case class Asin(child: Expression) extends UnaryMathExpression(math.asin, "ASIN")

case class Atan(child: Expression) extends UnaryMathExpression(math.atan, "ATAN")

case class Cbrt(child: Expression) extends UnaryMathExpression(math.cbrt, "CBRT")

case class Ceil(child: Expression) extends UnaryMathExpression(math.ceil, "CEIL")

case class Cos(child: Expression) extends UnaryMathExpression(math.cos, "COS")

case class Cosh(child: Expression) extends UnaryMathExpression(math.cosh, "COSH")

/**
 * Convert a num from one base to another
 * @param numExpr the number to be converted
 * @param fromBaseExpr from which base
 * @param toBaseExpr to which base
 */
case class Conv(numExpr: Expression, fromBaseExpr: Expression, toBaseExpr: Expression)
  extends Expression with ImplicitCastInputTypes{

  override def foldable: Boolean = numExpr.foldable && fromBaseExpr.foldable && toBaseExpr.foldable

  override def nullable: Boolean = numExpr.nullable || fromBaseExpr.nullable || toBaseExpr.nullable

  override def children: Seq[Expression] = Seq(numExpr, fromBaseExpr, toBaseExpr)

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, IntegerType, IntegerType)

  /** Returns the result of evaluating this expression on a given input Row */
  override def eval(input: InternalRow): Any = {
    val num = numExpr.eval(input)
    val fromBase = fromBaseExpr.eval(input)
    val toBase = toBaseExpr.eval(input)
    if (num == null || fromBase == null || toBase == null) {
      null
    } else {
      conv(num.asInstanceOf[UTF8String].getBytes,
        fromBase.asInstanceOf[Int], toBase.asInstanceOf[Int])
    }
  }

  /**
   * Returns the [[DataType]] of the result of evaluating this expression.  It is
   * invalid to query the dataType of an unresolved expression (i.e., when `resolved` == false).
   */
  override def dataType: DataType = StringType

  private val value = new Array[Byte](64)

  /**
   * Divide x by m as if x is an unsigned 64-bit integer. Examples:
   * unsignedLongDiv(-1, 2) == Long.MAX_VALUE unsignedLongDiv(6, 3) == 2
   * unsignedLongDiv(0, 5) == 0
   *
   * @param x is treated as unsigned
   * @param m is treated as signed
   */
  private def unsignedLongDiv(x: Long, m: Int): Long = {
    if (x >= 0) {
      x / m
    } else {
      // Let uval be the value of the unsigned long with the same bits as x
      // Two's complement => x = uval - 2*MAX - 2
      // => uval = x + 2*MAX + 2
      // Now, use the fact: (a+b)/c = a/c + b/c + (a%c+b%c)/c
      (x / m + 2 * (Long.MaxValue / m) + 2 / m + (x % m + 2 * (Long.MaxValue % m) + 2 % m) / m)
    }
  }

  /**
   * Decode v into value[].
   *
   * @param v is treated as an unsigned 64-bit integer
   * @param radix must be between MIN_RADIX and MAX_RADIX
   */
  private def decode(v: Long, radix: Int): Unit = {
    var tmpV = v
    Arrays.fill(value, 0.asInstanceOf[Byte])
    var i = value.length - 1
    while (tmpV != 0) {
      val q = unsignedLongDiv(tmpV, radix)
      value(i) = (tmpV - q * radix).asInstanceOf[Byte]
      tmpV = q
      i -= 1
    }
  }

  /**
   * Convert value[] into a long. On overflow, return -1 (as mySQL does). If a
   * negative digit is found, ignore the suffix starting there.
   *
   * @param radix  must be between MIN_RADIX and MAX_RADIX
   * @param fromPos is the first element that should be conisdered
   * @return the result should be treated as an unsigned 64-bit integer.
   */
  private def encode(radix: Int, fromPos: Int): Long = {
    var v: Long = 0L
    val bound = unsignedLongDiv(-1 - radix, radix) // Possible overflow once
    // val
    // exceeds this value
    var i = fromPos
    while (i < value.length && value(i) >= 0) {
      if (v >= bound) {
        // Check for overflow
        if (unsignedLongDiv(-1 - value(i), radix) < v) {
          return -1
        }
      }
      v = v * radix + value(i)
      i += 1
    }
    return v
  }

  /**
   * Convert the bytes in value[] to the corresponding chars.
   *
   * @param radix must be between MIN_RADIX and MAX_RADIX
   * @param fromPos is the first nonzero element
   */
  private def byte2char(radix: Int, fromPos: Int): Unit = {
    var i = fromPos
    while (i < value.length) {
      value(i) = Character.toUpperCase(Character.forDigit(value(i), radix)).asInstanceOf[Byte]
      i += 1
    }
  }

  /**
   * Convert the chars in value[] to the corresponding integers. Convert invalid
   * characters to -1.
   *
   * @param radix must be between MIN_RADIX and MAX_RADIX
   * @param fromPos is the first nonzero element
   */
  private def char2byte(radix: Int, fromPos: Int): Unit = {
    var i = fromPos
    while ( i < value.length) {
      value(i) = Character.digit(value(i), radix).asInstanceOf[Byte]
      i += 1
    }
  }

  /**
   * Convert numbers between different number bases. If toBase>0 the result is
   * unsigned, otherwise it is signed.
   * NB: This logic is borrowed from org.apache.hadoop.hive.ql.ud.UDFConv
   */
  private def conv(n: Array[Byte] , fromBase: Int, toBase: Int ): UTF8String = {
    if (n == null || fromBase == null || toBase == null || n.isEmpty) {
      return null
    }

    if (fromBase < Character.MIN_RADIX || fromBase > Character.MAX_RADIX
      || Math.abs(toBase) < Character.MIN_RADIX
      || Math.abs(toBase) > Character.MAX_RADIX) {
      return null
    }

    var (negative, first) = if (n(0) == '-') (true, 1) else (false, 0)

    // Copy the digits in the right side of the array
    var i = 1
    while (i <= n.length - first) {
      value(value.length - i) = n(n.length - i)
      i += 1
    }
    char2byte(fromBase, value.length - n.length + first)

    // Do the conversion by going through a 64 bit integer
    var v = encode(fromBase, value.length - n.length + first)
    if (negative && toBase > 0) {
      if (v < 0) {
        v = -1
      } else {
        v = -v
      }
    }
    if (toBase < 0 && v < 0) {
      v = -v
      negative = true
    }
    decode(v, Math.abs(toBase))

    // Find the first non-zero digit or the last digits if all are zero.
    val firstNonZeroPos = {
      val firstNonZero = value.indexWhere( _ != 0)
      if (firstNonZero != -1) firstNonZero else value.length - 1
    }

    byte2char(Math.abs(toBase), firstNonZeroPos)

    var resultStartPos = firstNonZeroPos
    if (negative && toBase < 0) {
      resultStartPos = firstNonZeroPos - 1
      value(resultStartPos) = '-'
    }
    UTF8String.fromBytes( Arrays.copyOfRange(value, resultStartPos, value.length))
  }
}

case class Exp(child: Expression) extends UnaryMathExpression(math.exp, "EXP")

case class Expm1(child: Expression) extends UnaryMathExpression(math.expm1, "EXPM1")

case class Floor(child: Expression) extends UnaryMathExpression(math.floor, "FLOOR")

object Factorial {

  def factorial(n: Int): Long = {
    if (n < factorials.length) factorials(n) else Long.MaxValue
  }

  private val factorials: Array[Long] = Array[Long](
    1,
    1,
    2,
    6,
    24,
    120,
    720,
    5040,
    40320,
    362880,
    3628800,
    39916800,
    479001600,
    6227020800L,
    87178291200L,
    1307674368000L,
    20922789888000L,
    355687428096000L,
    6402373705728000L,
    121645100408832000L,
    2432902008176640000L
  )
}

case class Factorial(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {

  override def inputTypes: Seq[DataType] = Seq(IntegerType)

  override def dataType: DataType = LongType

  // If the value not in the range of [0, 20], it still will be null, so set it to be true here.
  override def nullable: Boolean = true

  protected override def nullSafeEval(input: Any): Any = {
    val value = input.asInstanceOf[jl.Integer]
    if (value > 20 || value < 0) {
      null
    } else {
      Factorial.factorial(value)
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        if ($eval > 20 || $eval < 0) {
          ${ev.isNull} = true;
        } else {
          ${ev.primitive} =
            org.apache.spark.sql.catalyst.expressions.Factorial.factorial($eval);
        }
      """
    })
  }
}

case class Log(child: Expression) extends UnaryMathExpression(math.log, "LOG")

case class Log2(child: Expression)
  extends UnaryMathExpression((x: Double) => math.log(x) / math.log(2), "LOG2") {
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        ${ev.primitive} = java.lang.Math.log($eval) / java.lang.Math.log(2);
        if (Double.valueOf(${ev.primitive}).isNaN()) {
          ${ev.isNull} = true;
        }
      """
    })
  }
}

case class Log10(child: Expression) extends UnaryMathExpression(math.log10, "LOG10")

case class Log1p(child: Expression) extends UnaryMathExpression(math.log1p, "LOG1P")

case class Rint(child: Expression) extends UnaryMathExpression(math.rint, "ROUND") {
  override def funcName: String = "rint"
}

case class Signum(child: Expression) extends UnaryMathExpression(math.signum, "SIGNUM")

case class Sin(child: Expression) extends UnaryMathExpression(math.sin, "SIN")

case class Sinh(child: Expression) extends UnaryMathExpression(math.sinh, "SINH")

case class Sqrt(child: Expression) extends UnaryMathExpression(math.sqrt, "SQRT")

case class Tan(child: Expression) extends UnaryMathExpression(math.tan, "TAN")

case class Tanh(child: Expression) extends UnaryMathExpression(math.tanh, "TANH")

case class ToDegrees(child: Expression) extends UnaryMathExpression(math.toDegrees, "DEGREES") {
  override def funcName: String = "toDegrees"
}

case class ToRadians(child: Expression) extends UnaryMathExpression(math.toRadians, "RADIANS") {
  override def funcName: String = "toRadians"
}

case class Bin(child: Expression)
  extends UnaryExpression with Serializable with ImplicitCastInputTypes {

  override def inputTypes: Seq[DataType] = Seq(LongType)
  override def dataType: DataType = StringType

  protected override def nullSafeEval(input: Any): Any =
    UTF8String.fromString(jl.Long.toBinaryString(input.asInstanceOf[Long]))

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c) =>
      s"UTF8String.fromString(java.lang.Long.toBinaryString($c))")
  }
}

object Hex {
  val hexDigits = Array[Char](
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
  ).map(_.toByte)

  // lookup table to translate '0' -> 0 ... 'F'/'f' -> 15
  val unhexDigits = {
    val array = Array.fill[Byte](128)(-1)
    (0 to 9).foreach(i => array('0' + i) = i.toByte)
    (0 to 5).foreach(i => array('A' + i) = (i + 10).toByte)
    (0 to 5).foreach(i => array('a' + i) = (i + 10).toByte)
    array
  }
}

/**
 * If the argument is an INT or binary, hex returns the number as a STRING in hexadecimal format.
 * Otherwise if the number is a STRING, it converts each character into its hex representation
 * and returns the resulting STRING. Negative numbers would be treated as two's complement.
 */
case class Hex(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {
  // TODO: Create code-gen version.

  override def inputTypes: Seq[AbstractDataType] =
    Seq(TypeCollection(LongType, BinaryType, StringType))

  override def dataType: DataType = StringType

  protected override def nullSafeEval(num: Any): Any = child.dataType match {
    case LongType => hex(num.asInstanceOf[Long])
    case BinaryType => hex(num.asInstanceOf[Array[Byte]])
    case StringType => hex(num.asInstanceOf[UTF8String].getBytes)
  }

  private[this] def hex(bytes: Array[Byte]): UTF8String = {
    val length = bytes.length
    val value = new Array[Byte](length * 2)
    var i = 0
    while (i < length) {
      value(i * 2) = Hex.hexDigits((bytes(i) & 0xF0) >> 4)
      value(i * 2 + 1) = Hex.hexDigits(bytes(i) & 0x0F)
      i += 1
    }
    UTF8String.fromBytes(value)
  }

  private def hex(num: Long): UTF8String = {
    // Extract the hex digits of num into value[] from right to left
    val value = new Array[Byte](16)
    var numBuf = num
    var len = 0
    do {
      len += 1
      value(value.length - len) = Hex.hexDigits((numBuf & 0xF).toInt)
      numBuf >>>= 4
    } while (numBuf != 0)
    UTF8String.fromBytes(java.util.Arrays.copyOfRange(value, value.length - len, value.length))
  }
}

/**
 * Performs the inverse operation of HEX.
 * Resulting characters are returned as a byte array.
 */
case class Unhex(child: Expression) extends UnaryExpression with ImplicitCastInputTypes {
  // TODO: Create code-gen version.

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType)

  override def nullable: Boolean = true
  override def dataType: DataType = BinaryType

  protected override def nullSafeEval(num: Any): Any =
    unhex(num.asInstanceOf[UTF8String].getBytes)

  private[this] def unhex(bytes: Array[Byte]): Array[Byte] = {
    val out = new Array[Byte]((bytes.length + 1) >> 1)
    var i = 0
    if ((bytes.length & 0x01) != 0) {
      // padding with '0'
      if (bytes(0) < 0) {
        return null
      }
      val v = Hex.unhexDigits(bytes(0))
      if (v == -1) {
        return null
      }
      out(0) = v
      i += 1
    }
    // two characters form the hex value.
    while (i < bytes.length) {
      if (bytes(i) < 0 || bytes(i + 1) < 0) {
        return null
      }
      val first = Hex.unhexDigits(bytes(i))
      val second = Hex.unhexDigits(bytes(i + 1))
      if (first == -1 || second == -1) {
        return null
      }
      out(i / 2) = (((first << 4) | second) & 0xFF).toByte
      i += 2
    }
    out
  }
}


////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////
// Binary math functions
////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////


case class Atan2(left: Expression, right: Expression)
  extends BinaryMathExpression(math.atan2, "ATAN2") {

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    // With codegen, the values returned by -0.0 and 0.0 are different. Handled with +0.0
    val result = math.atan2(input1.asInstanceOf[Double] + 0.0, input2.asInstanceOf[Double] + 0.0)
    if (result.isNaN) null else result
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"java.lang.Math.atan2($c1 + 0.0, $c2 + 0.0)") + s"""
      if (Double.valueOf(${ev.primitive}).isNaN()) {
        ${ev.isNull} = true;
      }
      """
  }
}

case class Pow(left: Expression, right: Expression)
  extends BinaryMathExpression(math.pow, "POWER") {
  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => s"java.lang.Math.pow($c1, $c2)") + s"""
      if (Double.valueOf(${ev.primitive}).isNaN()) {
        ${ev.isNull} = true;
      }
      """
  }
}


/**
 * Bitwise unsigned left shift.
 * @param left the base number to shift.
 * @param right number of bits to left shift.
 */
case class ShiftLeft(left: Expression, right: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] =
    Seq(TypeCollection(IntegerType, LongType), IntegerType)

  override def dataType: DataType = left.dataType

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    input1 match {
      case l: jl.Long => l << input2.asInstanceOf[jl.Integer]
      case i: jl.Integer => i << input2.asInstanceOf[jl.Integer]
    }
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (left, right) => s"$left << $right")
  }
}


/**
 * Bitwise unsigned left shift.
 * @param left the base number to shift.
 * @param right number of bits to left shift.
 */
case class ShiftRight(left: Expression, right: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] =
    Seq(TypeCollection(IntegerType, LongType), IntegerType)

  override def dataType: DataType = left.dataType

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    input1 match {
      case l: jl.Long => l >> input2.asInstanceOf[jl.Integer]
      case i: jl.Integer => i >> input2.asInstanceOf[jl.Integer]
    }
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (left, right) => s"$left >> $right")
  }
}


/**
 * Bitwise unsigned right shift, for integer and long data type.
 * @param left the base number.
 * @param right the number of bits to right shift.
 */
case class ShiftRightUnsigned(left: Expression, right: Expression)
  extends BinaryExpression with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] =
    Seq(TypeCollection(IntegerType, LongType), IntegerType)

  override def dataType: DataType = left.dataType

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    input1 match {
      case l: jl.Long => l >>> input2.asInstanceOf[jl.Integer]
      case i: jl.Integer => i >>> input2.asInstanceOf[jl.Integer]
    }
  }

  override protected def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (left, right) => s"$left >>> $right")
  }
}


case class Hypot(left: Expression, right: Expression)
  extends BinaryMathExpression(math.hypot, "HYPOT")


/**
 * Computes the logarithm of a number.
 * @param left the logarithm base, default to e.
 * @param right the number to compute the logarithm of.
 */
case class Logarithm(left: Expression, right: Expression)
  extends BinaryMathExpression((c1, c2) => math.log(c2) / math.log(c1), "LOG") {

  /**
   * Natural log, i.e. using e as the base.
   */
  def this(child: Expression) = {
    this(EulerNumber(), child)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val logCode = if (left.isInstanceOf[EulerNumber]) {
      defineCodeGen(ctx, ev, (c1, c2) => s"java.lang.Math.log($c2)")
    } else {
      defineCodeGen(ctx, ev, (c1, c2) => s"java.lang.Math.log($c2) / java.lang.Math.log($c1)")
    }
    logCode + s"""
      if (Double.isNaN(${ev.primitive})) {
        ${ev.isNull} = true;
      }
    """
  }
}

/**
 * Round the `child`'s result to `scale` decimal place when `scale` >= 0
 * or round at integral part when `scale` < 0.
 * For example, round(31.415, 2) would eval to 31.42 and round(31.415, -1) would eval to 30.
 *
 * Child of IntegralType would eval to itself when `scale` >= 0.
 * Child of FractionalType whose value is NaN or Infinite would always eval to itself.
 *
 * Round's dataType would always equal to `child`'s dataType except for [[DecimalType.Fixed]],
 * which leads to scale update in DecimalType's [[PrecisionInfo]]
 *
 * @param child expr to be round, all [[NumericType]] is allowed as Input
 * @param scale new scale to be round to, this should be a constant int at runtime
 */
case class Round(child: Expression, scale: Expression)
  extends BinaryExpression with ExpectsInputTypes {

  import BigDecimal.RoundingMode.HALF_UP

  def this(child: Expression) = this(child, Literal(0))

  override def left: Expression = child
  override def right: Expression = scale

  // round of Decimal would eval to null if it fails to `changePrecision`
  override def nullable: Boolean = true

  override def foldable: Boolean = child.foldable

  override lazy val dataType: DataType = child.dataType match {
    // if the new scale is bigger which means we are scaling up,
    // keep the original scale as `Decimal` does
    case DecimalType.Fixed(p, s) => DecimalType(p, if (_scale > s) s else _scale)
    case t => t
  }

  override def inputTypes: Seq[AbstractDataType] = Seq(NumericType, IntegerType)

  override def checkInputDataTypes(): TypeCheckResult = {
    super.checkInputDataTypes() match {
      case TypeCheckSuccess =>
        if (scale.foldable) {
          TypeCheckSuccess
        } else {
          TypeCheckFailure("Only foldable Expression is allowed for scale arguments")
        }
      case f => f
    }
  }

  // Avoid repeated evaluation since `scale` is a constant int,
  // avoid unnecessary `child` evaluation in both codegen and non-codegen eval
  // by checking if scaleV == null as well.
  private lazy val scaleV: Any = scale.eval(EmptyRow)
  private lazy val _scale: Int = scaleV.asInstanceOf[Int]

  override def eval(input: InternalRow): Any = {
    if (scaleV == null) { // if scale is null, no need to eval its child at all
      null
    } else {
      val evalE = child.eval(input)
      if (evalE == null) {
        null
      } else {
        nullSafeEval(evalE)
      }
    }
  }

  // not overriding since _scale is a constant int at runtime
  def nullSafeEval(input1: Any): Any = {
    child.dataType match {
      case _: DecimalType =>
        val decimal = input1.asInstanceOf[Decimal]
        if (decimal.changePrecision(decimal.precision, _scale)) decimal else null
      case ByteType =>
        BigDecimal(input1.asInstanceOf[Byte]).setScale(_scale, HALF_UP).toByte
      case ShortType =>
        BigDecimal(input1.asInstanceOf[Short]).setScale(_scale, HALF_UP).toShort
      case IntegerType =>
        BigDecimal(input1.asInstanceOf[Int]).setScale(_scale, HALF_UP).toInt
      case LongType =>
        BigDecimal(input1.asInstanceOf[Long]).setScale(_scale, HALF_UP).toLong
      case FloatType =>
        val f = input1.asInstanceOf[Float]
        if (f.isNaN || f.isInfinite) {
          f
        } else {
          BigDecimal(f).setScale(_scale, HALF_UP).toFloat
        }
      case DoubleType =>
        val d = input1.asInstanceOf[Double]
        if (d.isNaN || d.isInfinite) {
          d
        } else {
          BigDecimal(d).setScale(_scale, HALF_UP).toDouble
        }
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val ce = child.gen(ctx)

    val evaluationCode = child.dataType match {
      case _: DecimalType =>
        s"""
        if (${ce.primitive}.changePrecision(${ce.primitive}.precision(), ${_scale})) {
          ${ev.primitive} = ${ce.primitive};
        } else {
          ${ev.isNull} = true;
        }"""
      case ByteType =>
        if (_scale < 0) {
          s"""
          ${ev.primitive} = new java.math.BigDecimal(${ce.primitive}).
            setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).byteValue();"""
        } else {
          s"${ev.primitive} = ${ce.primitive};"
        }
      case ShortType =>
        if (_scale < 0) {
          s"""
          ${ev.primitive} = new java.math.BigDecimal(${ce.primitive}).
            setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).shortValue();"""
        } else {
          s"${ev.primitive} = ${ce.primitive};"
        }
      case IntegerType =>
        if (_scale < 0) {
          s"""
          ${ev.primitive} = new java.math.BigDecimal(${ce.primitive}).
            setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).intValue();"""
        } else {
          s"${ev.primitive} = ${ce.primitive};"
        }
      case LongType =>
        if (_scale < 0) {
          s"""
          ${ev.primitive} = new java.math.BigDecimal(${ce.primitive}).
            setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).longValue();"""
        } else {
          s"${ev.primitive} = ${ce.primitive};"
        }
      case FloatType => // if child eval to NaN or Infinity, just return it.
        if (_scale == 0) {
          s"""
            if (Float.isNaN(${ce.primitive}) || Float.isInfinite(${ce.primitive})){
              ${ev.primitive} = ${ce.primitive};
            } else {
              ${ev.primitive} = Math.round(${ce.primitive});
            }"""
        } else {
          s"""
            if (Float.isNaN(${ce.primitive}) || Float.isInfinite(${ce.primitive})){
              ${ev.primitive} = ${ce.primitive};
            } else {
              ${ev.primitive} = java.math.BigDecimal.valueOf(${ce.primitive}).
                setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).floatValue();
            }"""
        }
      case DoubleType => // if child eval to NaN or Infinity, just return it.
        if (_scale == 0) {
          s"""
            if (Double.isNaN(${ce.primitive}) || Double.isInfinite(${ce.primitive})){
              ${ev.primitive} = ${ce.primitive};
            } else {
              ${ev.primitive} = Math.round(${ce.primitive});
            }"""
        } else {
          s"""
            if (Double.isNaN(${ce.primitive}) || Double.isInfinite(${ce.primitive})){
              ${ev.primitive} = ${ce.primitive};
            } else {
              ${ev.primitive} = java.math.BigDecimal.valueOf(${ce.primitive}).
                setScale(${_scale}, java.math.BigDecimal.ROUND_HALF_UP).doubleValue();
            }"""
        }
    }

    if (scaleV == null) { // if scale is null, no need to eval its child at all
      s"""
        boolean ${ev.isNull} = true;
        ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
      """
    } else {
      s"""
        ${ce.code}
        boolean ${ev.isNull} = ${ce.isNull};
        ${ctx.javaType(dataType)} ${ev.primitive} = ${ctx.defaultValue(dataType)};
        if (!${ev.isNull}) {
          $evaluationCode
        }
      """
    }
  }

  override def prettyName: String = "round"
}
