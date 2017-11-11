/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj

import scala.language.existentials

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.collection.AbstractIterator

import org.opalj.log.GlobalLogContext
import org.opalj.log.OPALLogger
import org.opalj.util.AnyToAnyThis
import org.opalj.collection.IntIterator
import org.opalj.collection.immutable.IntTrieSet
import org.opalj.collection.immutable.Chain
import org.opalj.br.Method
import org.opalj.br.MethodDescriptor
import org.opalj.br.Code
import org.opalj.br.instructions.Instruction

/**
 * Implementation of an abstract interpretation (ai) framework – also referred to as OPAL.
 *
 * Please note, that OPAL/the abstract interpreter just refers to the classes and traits
 * defined in this package (`ai`). The classes and traits defined in the sub-packages
 * (in particular in `domain`) are not considered to be part of the core of OPAL/the
 * abstract interpreter.
 *
 * @note This framework assumes that the analyzed bytecode is valid; i.e., the JVM's
 *      bytecode verifier would be able to verify the code. Furthermore, load-time errors
 *      (e.g., `LinkageErrors`) are – by default – completely ignored to facilitate the
 *      analysis of parts of a project. In general, if the presented bytecode is not valid,
 *      the result is undefined (i.e., OPAL may report meaningless results, crash or run
 *      indefinitely).
 *
 * @see [[org.opalj.ai.AI]] - Implements the abstract interpreter that
 *      processes a methods code and uses an analysis-specific domain to perform the
 *      abstract computations.
 * @see [[org.opalj.ai.Domain]] - The core interface between the abstract
 *      interpretation framework and the abstract domain that is responsible for
 *      performing the abstract computations.
 *
 * @author Michael Eichberg
 */
package object ai {

    final val FrameworkName = "OPAL Abstract Interpretation Framework"

    {
        implicit val logContext = GlobalLogContext
        import OPALLogger.info
        try {
            assert(false) // <= tests whether assertions are on or off...
            info(FrameworkName, "Production Build")
        } catch {
            case _: AssertionError ⇒ info(FrameworkName, "Development Build with Assertions")
        }
    }

    // We want to make sure that the class loader is used which potentially can
    // find the config files; the libraries (e.g., Typesafe Config) may have
    // been loaded using the parent class loader and, hence, may not be able to
    // find the config files at all.
    val BaseConfig: Config = ConfigFactory.load(this.getClass.getClassLoader())

    /**
     * Type alias that can be used if the AI can use all kinds of domains.
     *
     * @note This type alias serves comprehension purposes only.
     */
    type SomeAI[D <: Domain] = AI[_ >: D]

    type PrimitiveValuesFactory = IntegerValuesFactory with LongValuesFactory with FloatValuesFactory with DoubleValuesFactory
    type ValuesFactory = PrimitiveValuesFactory with ReferenceValuesFactory with ExceptionsFactory with TypedValuesFactory
    type TargetDomain = ValuesDomain with ValuesFactory

    type PC = org.opalj.br.PC
    type PCs = org.opalj.br.PCs
    final def NoPCs = org.opalj.br.NoPCs

    /**
     * A `ValueOrigin` identifies the origin of a value within a method.
     * In most cases the origin is equal to the program counter of the instruction that created
     * the value. However, several negative values do have special semantics which are explained
     * in the following.
     *
     * == Parameter Identification ==
     *
     * In general, parameters are identified by using negative origin information as described below.
     * But, given that
     *  - the maximum size of the method parameters array is 255 and
     *  - that the first slot is required for the `this` reference in case of instance methods and
     *  - that `long` and `double` values* require two slots
     * the smallest number used to encode that the value is an actual parameter is `-256`.
     *
     * === AI Framework ===
     *
     * In case of the ai framework, values passed to a method get indexes as follows:
     *  `-1-(isStatic ? 0 : 1)-(the index of the parameter adjusted by the computational
     * type of the previous parameters)`.
     *
     * For example, in case of an instance method with the signature:
     * {{{
     * public void (double d/*parameter index:0*/, Object o/*parameter index:1*/){...}
     * }}}
     *
     *  - The value `-1` is used to identify the implicit `this` reference.
     *
     *  - The value `-2` identifies the value of the parameter `d`.
     *
     *  - The value `-4` identifies the parameter `o`. (The parameter `d` is a value of
     * computational-type category 2 and needs two stack/operands values.)
     *
     * === Three-address Code ===
     * In case of the three address code the parameters are normalized (see [[org.opalj.tac.TACAI]]
     * for further details).
     *
     * == Subroutines JSR/RET ==
     * Some special values are used when methods have subroutines:
     * ([[SUBROUTINE_START]], [[SUBROUTINE_END]], [[SUBROUTINE]]). These methods, never show
     * up at the def-use or cfg level, but will show up in the evaluation trace.
     *
     * == Implicit JVM Constants ==
     * The value `-333` is used to encode that the value is an implicit constant
     * ([[ConstantValueOrigin]]). This value is used for the implicit value of `IF_XXX`
     * instructions to facilitates a generalized handling of ifs.
     *
     * Values in the range [ [[SpecialValuesOriginOffset]] (`-10,000,000`) ,
     * [[VMLevelValuesOriginOffset]] (`-100,000`) ] are used to identify values that are
     * created outside of the method, but due to the evaluation of the instruction with
     * the `pc = -origin-100,000` (in particular exceptions).
     *
     * @see [[isVMLevelValue]], [[ValueOriginForVMLevelValue]], [[pcOfVMLevelValue]]
     */
    type ValueOrigin = Int
    type ValueOrigins = IntTrieSet
    type ValueOriginsIterator = IntIterator

    /**
     * Identifies the ''upper bound for those origin values that encode origin
     * information about VM level values'' (that is, VM generated exceptions).
     */
    final val VMLevelValuesOriginOffset /*: ValueOrigin*/ = -100000 // TODO Rename MethodExternalOriginOffset

    /**
     * Identifies the upper bound for those "origin values" that encode special information;
     * that is, subroutine boundaries.
     */
    final val SpecialValuesOriginOffset /*: ValueOrigin*/ = -10000000

    /**
     * Returns `true` if the value with the given origin was (implicitly) created
     * by the JVM while executing an instruction with the program counter
     * [[pcOfVMLevelValue]]`(origin)`.
     *
     * @see [[ValueOriginForVMLevelValue]] for further information.
     */
    final def isVMLevelValue(origin: ValueOrigin): Boolean = { // TODO Rename isMethodExternalValue
        origin <= VMLevelValuesOriginOffset && origin > SpecialValuesOriginOffset
    }

    /**
     * Creates the origin information for a value (typically an exception) that
     * was (implicitly) created while evaluating the instruction with the given
     * program counter (`pc`).
     *
     * @see [[pcOfVMLevelValue]] for further information.
     */
    final def ValueOriginForVMLevelValue(pc: PC): ValueOrigin = { //TODO Rename valueOriginForMethodExternalValue
        val origin = VMLevelValuesOriginOffset - pc
        assert(
            origin <= VMLevelValuesOriginOffset,
            s"[pc:$pc] origin($origin) > VMLevelValuesOriginOffset($VMLevelValuesOriginOffset)"
        )
        assert(origin > SpecialValuesOriginOffset)
        origin
    }

    /**
     * Returns the program counter (`pc`) of the instruction that (implicitly) led to the
     * creation of the (method external) value (typically an `Exception`).
     *
     * @see [[ValueOriginForVMLevelValue]] for further information.
     */
    final def pcOfVMLevelValue(origin: ValueOrigin): PC = { //TODO Rename pcOfMethodExternalValue
        assert(origin <= VMLevelValuesOriginOffset)
        -origin + VMLevelValuesOriginOffset
    }

    /**
     * Used to identify that the origin of the value is outside of the program.
     *
     * For example, the VM sometimes performs comparisons against predetermined fixed
     * values (specified in the JVM Spec.). The origin associated with such values is
     * determined by this value.
     */
    final val ConstantValueOrigin /*: ValueOrigin*/ = -333

    /**
     * Calculates the initial `ValueOrigin` associated with a method's explicit parameter.
     * The index of the first parameter is 0. If the method is not static the this reference
     * stored in local variable `0` has the origin `-1`.
     *
     * @param   isStatic `true` if method is static and, hence, has no implicit
     *          parameter for `this`.
     * @see     [[mapOperandsToParameters]]
     */
    def parameterIndexToValueOrigin(
        isStatic:       Boolean,
        descriptor:     MethodDescriptor,
        parameterIndex: Int
    ): ValueOrigin = {
        assert(descriptor.parametersCount > 0)

        var origin = if (isStatic) -1 else -2 // this handles the case parameterIndex == 0
        val parameterTypes = descriptor.parameterTypes
        var currentIndex = 0
        while (currentIndex < parameterIndex) {
            origin -= parameterTypes(currentIndex).computationalType.operandSize
            currentIndex += 1
        }
        origin
    }

    /**
     * Special value ("pc") that is added to the ''work list''/''list of evaluated instructions''
     * before the '''program counter of the first instruction''' of a subroutine.
     *
     * The marker [[SUBROUTINE]] is used to mark the place in the worklist where we
     * start having information about subroutines.
     */
    // Some value smaller than -65536 to avoid confusion with local variable indexes.
    final val SUBROUTINE_START = -80000008

    /**
     * Special value ("pc") that is added to the list of `evaluated instructions`
     * to mark the end of the evaluation of a subroutine.
     */
    final val SUBROUTINE_END = -88888888

    /**
     * A special value that is larger than all other values used to mark boundaries
     * and information related to the handling of subroutines and which is smaller
     * that all other regular values.
     */
    final val SUBROUTINE_INFORMATION_BLOCK_SEPARATOR_BOUND = -80000000

    final val SUBROUTINE_RETURN_ADDRESS_LOCAL_VARIABLE = -88880008

    final val SUBROUTINE_RETURN_TO_TARGET = -80008888

    /**
     * Special value that is added to the work list to mark the beginning of a
     * subroutine call.
     */
    final val SUBROUTINE = -90000009 // some value smaller than -2^16

    type Operands[T >: Null <: ValuesDomain#DomainValue] = Chain[T]
    type AnOperandsArray[T >: Null <: ValuesDomain#DomainValue] = Array[Operands[T]]
    type TheOperandsArray[T >: Null <: d.Operands forSome { val d: ValuesDomain }] = Array[T]

    type Locals[T >: Null <: ValuesDomain#DomainValue] = org.opalj.collection.mutable.Locals[T]
    type ALocalsArray[T >: Null <: ValuesDomain#DomainValue] = Array[Locals[T]]
    type TheLocalsArray[T >: Null <: d.Locals forSome { val d: ValuesDomain }] = Array[T]

    /**
     * Creates a human-readable textual representation of the current memory layout.
     */
    def memoryLayoutToText(
        domain: Domain
    )(
        operandsArray: domain.OperandsArray,
        localsArray:   domain.LocalsArray
    ): String = {
        val operandsAndLocals =
            for {
                ((operands, locals), pc) ← operandsArray.zip(localsArray).zipWithIndex
                if operands != null /*|| locals != null*/
            } yield {
                val localsWithIndex =
                    for { (l, idx) ← locals.zipWithIndex if l ne null } yield { s"($idx:$l)" }

                operands.mkString(s"PC: $pc\n\tOperands: ", " <- ", "") +
                    localsWithIndex.mkString("\n\tLocals: [", ",", "]")
            }
        operandsAndLocals.mkString("Operands and Locals:\n", "\n", "\n")
    }

    /**
     * Extracts the domain variables (register values) related to the method's parameters;
     * see [[org.opalj.tac.Parameters]] for the detailed layout of the returned array.
     *
     * Recall that at the bytecode level long and double values use two register values. The
     * returned array will, however, abstract over the difference between so-called computational
     * type category I and II values. Furthermore, the explicitly specified parameters are
     * always stored in the indexes [1..parametersCount] to enable unifor access to a method's
     * parameters whether the method is static or not. Furthermore, the returned array will
     * contain the self reference (`this`) at index 0 if the method is an instance method;
     * otherwise index 0 will be `null`.
     *
     * @note   If a parameter (variable) is used as a variable and updated,
     *         then the returned domain value will reflect this behavior.
     *         For example, given the following code:
     *         {{{
     *         // Given: class X extends Object
     *         foo(X x) { do { x = new Y(); System.out.println(x) } while(true;)}
     *         }}}
     *         The type of the domain value will be (as expected) x; however - depending on the
     *         domain - it may contain the information that x may also reference the created
     *         object Y.
     *
     * @param  isStatic `true` if the method is static (we have no `this` reference).
     * @param  descriptor The method descriptor.
     *
     * @return The local variables which represent the parameters. The size of the returned array
     *         is the sum of the operand sizes of the parameters + 1 if the method is an instance
     *         method. (@see [[parameterIndexToValueOrigin]] and [[mapOperandsToParameters]]
     *         for further details.)
     */
    def parameterVariables(
        aiResult: AIResult
    )(
        isStatic:   Boolean,
        descriptor: MethodDescriptor
    ): Array[aiResult.domain.DomainValue] = {
        val locals: Locals[aiResult.domain.DomainValue] = aiResult.localsArray(0)
        // To enable uniform access, we always reserve space for the `this` parameter;
        // even if it is not used.
        val parametersCount = descriptor.parametersCount + 1
        val params = aiResult.domain.DomainValue.newArray(parametersCount)

        var localsIndex = 0
        if (!isStatic) {
            params(0) = locals(0)
            localsIndex = 1
        }
        var paramIndex = 1
        descriptor.parameterTypes.foreach { t ⇒
            params(paramIndex) = locals(localsIndex)
            localsIndex += t.computationalType.operandSize
            paramIndex += 1
        }
        params
    }

    /**
     * Iterates over all im-/explicit parameter related variables.
     *
     * @param isStatic Has to be `true` iff the method for which the abstract interpretation was
     *                 performed is static.
     */
    def parameterVariablesIterator(
        aiResult: AIResult
    )(
        isStatic:   Boolean,
        descriptor: MethodDescriptor
    ): Iterator[aiResult.domain.DomainValue] = {
        new AbstractIterator[aiResult.domain.DomainValue] {

            private[this] var parameterIndex = 0
            private[this] val totalParameters = descriptor.parametersCount + (if (isStatic) 0 else 1)
            private[this] var localsIndex = 0

            override def hasNext: Boolean = parameterIndex < totalParameters

            override def next(): aiResult.domain.DomainValue = {
                if (parameterIndex == 0 && !isStatic) {
                    parameterIndex = 1
                    localsIndex = 1
                    aiResult.localsArray(0)(0)
                } else {
                    val v = aiResult.localsArray(0)(localsIndex)
                    parameterIndex += 1
                    localsIndex += v.computationalType.operandSize
                    v
                }
            }
        }
    }

    /**
     * Maps a list of operands (e.g., as passed to the `invokeXYZ` instructions) to
     * the list of parameters for the given method. The parameters are stored in the
     * local variables ([[Locals]])/registers of the method; i.e., this method
     * creates an initial assignment for the local variables that can directly
     * be used to pass them to [[AI]]'s
     * `perform(...)(<initialOperands = Nil>,initialLocals)` method.
     *
     * @param operands The list of operands used to call the given method. The length
     *      of the list must be:
     *      {{{
     *      calledMethod.descriptor.parametersCount + { if (calledMethod.isStatic) 0 else 1 }
     *      }}}.
     *      I.e., the list of operands must contain one value per parameter and – 
     *      in case of instance methods – the receiver object. The list __must not
     *       contain additional values__. The latter is automatically ensured if this
     *      method is called (in)directly by [[AI]] and the operands were just passed
     *      through.
     *      If two or more operands are (reference) identical then the adaptation will only
     *      be performed once and the adapted value will be reused; this ensures that
     *      the relation between values remains stable.
     * @param calledMethod The method that will be evaluated using the given operands.
     * @param targetDomain The [[Domain]] that will be use to perform the abstract
     *      interpretation.
     */
    def mapOperandsToParameters(
        operands:     Operands[_ <: ValuesDomain#DomainValue],
        calledMethod: Method,
        targetDomain: ValuesDomain with ValuesFactory
    ): Locals[targetDomain.DomainValue] = {

        assert(
            operands.size == calledMethod.actualArgumentsCount,
            (if (calledMethod.isStatic) "static " else "/*virtual*/ ") +
                s"${calledMethod.signatureToJava()}(Arguments: ${calledMethod.actualArgumentsCount}) "+
                s"${operands.mkString("Operands(", ",", ")")}"
        )

        import org.opalj.collection.mutable.Locals
        implicit val domainValue = targetDomain.DomainValue
        val parameters = Locals[targetDomain.DomainValue](calledMethod.body.get.maxLocals)
        var localVariableIndex = 0
        var processedOperands = 0
        val operandsInParameterOrder = operands.reverse
        operandsInParameterOrder foreach { operand ⇒
            val parameter = {
                // Was the same value (determined by "eq") already adapted?
                // If so, we reuse it to facilitate correlation analyses
                var pOperands = operandsInParameterOrder
                var pOperandIndex = 0
                var pLocalVariableIndex = 0
                while (pOperandIndex < processedOperands && (pOperands.head ne operand)) {
                    pOperandIndex += 1
                    pLocalVariableIndex += pOperands.head.computationalType.operandSize
                    pOperands = pOperands.tail
                }
                if (pOperandIndex < processedOperands) {
                    parameters(pLocalVariableIndex)
                } else {
                    // the value was not previously adapted
                    operand.adapt(targetDomain, -(processedOperands + 1))
                }
            }
            parameters(localVariableIndex) = parameter
            processedOperands += 1
            localVariableIndex += operand.computationalType.operandSize
        }

        parameters
    }

    /**
     * Maps the operands to the target domain while ensuring that two operands that
     * are identical before are identical afterwards.
     */
    def mapOperands(
        theOperands:  Operands[_ <: ValuesDomain#DomainValue],
        targetDomain: ValuesDomain with ValuesFactory
    ): Array[targetDomain.DomainValue] = {
        implicit val domainValue = targetDomain.DomainValue

        val operandsCount = theOperands.size
        val adaptedOperands = new Array[targetDomain.DomainValue](operandsCount)
        val processedOperands = new Array[Object](operandsCount)
        var remainingOperands = theOperands
        var i = 0
        def getIndex(operand: Object): Int = {
            var ii = 0
            while (ii < i) {
                if (processedOperands(i) eq operand)
                    return i;
                ii += 1
            }
            -1 // not found
        }

        while (remainingOperands.nonEmpty) {
            val nextOperand = remainingOperands.head
            val previousOperandIndex = getIndex(nextOperand)
            if (previousOperandIndex == -1)
                adaptedOperands(i) = nextOperand.adapt(targetDomain, i)
            else
                adaptedOperands(i) = adaptedOperands(previousOperandIndex)

            i += 1
            remainingOperands = remainingOperands.tail
        }

        adaptedOperands
    }

    /**
     * Collects the result of a match of a partial function against an instruction's
     * operands.
     */
    def collectPCWithOperands[B](
        domain: ValuesDomain
    )(
        code: Code, operandsArray: domain.OperandsArray
    )(
        f: PartialFunction[(PC, Instruction, domain.Operands), B]
    ): Seq[B] = {
        val instructions = code.instructions
        val max_pc = instructions.length
        var pc = 0
        val result = List.newBuilder[B]
        while (pc < max_pc) {
            val instruction = instructions(pc)
            val operands = operandsArray(pc)
            if (operands ne null) {
                val params = (pc, instruction, operands)
                val r: Any = f.applyOrElse(params, AnyToAnyThis)
                if (r.asInstanceOf[AnyRef] ne AnyToAnyThis) {
                    result += r.asInstanceOf[B]
                }
            }
            pc = instruction.indexOfNextInstruction(pc)(code)
        }
        result.result()
    }

    def foreachPCWithOperands[U](
        domain: ValuesDomain
    )(
        code: Code, operandsArray: domain.OperandsArray
    )(
        f: (PC, Instruction, domain.Operands) ⇒ U
    ): Unit = {
        val instructions = code.instructions
        val max_pc = instructions.size
        var pc = 0
        while (pc < max_pc) {
            val instruction = instructions(pc)
            val operands = operandsArray(pc)
            if (operands ne null) {
                f(pc, instruction, operands)
            }
            pc = instruction.indexOfNextInstruction(pc)(code)
        }
    }

    type ExceptionsRaisedByCalledMethod = ExceptionsRaisedByCalledMethods.Value
}
