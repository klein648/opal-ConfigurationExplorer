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
package ai
package domain

import java.lang.ref.{SoftReference ⇒ SRef}

import scala.collection.BitSet
import scala.collection.mutable
import org.opalj.collection.immutable.{Chain ⇒ List}
import org.opalj.collection.immutable.{Naught ⇒ Nil}
import org.opalj.collection.immutable.IntArraySet
import org.opalj.collection.immutable.IntArraySet1
import org.opalj.br.PC
import org.opalj.br.Code
import org.opalj.br.instructions.ReturnInstruction
import org.opalj.br.instructions.ATHROW
import org.opalj.graphs.DefaultMutableNode
import org.opalj.graphs.DominatorTree
import org.opalj.graphs.PostDominatorTree
import org.opalj.graphs.ControlDependencies
import org.opalj.br.cfg.CFG
import org.opalj.br.cfg.ExitNode
import org.opalj.br.cfg.BasicBlock
import org.opalj.br.cfg.CatchNode
import org.opalj.br.ExceptionHandler
import org.opalj.collection.mutable.IntArrayStack
import org.opalj.graphs.DominanceFrontiers

/**
 * Records the abstract interpretation time control-flow graph (CFG).
 * This CFG is always (still) a sound approximation of the generally incomputable
 * real(runtime) CFG.
 *
 * ==Usage (Mixin-Composition Order)==
 * This domain primarily overrides the `flow` method and requires that it is mixed in before every
 * other domain that overrides the `flow` method and which may manipulate the `worklist`.
 * E.g., the mixin order should be:
 * {{{ class MyDomain extends Domain with RecordCFG with FlowManipulatingDomain }}}
 * If the mixin order is not correct, the computed CFG may not be complete/concrete.
 *
 * ==Core Properties==
 *  - Thread-safe: '''No'''; i.e., the composed domain can only be used by one
 *              abstract interpreter at a time.
 *              However, using the collected results is thread-safe!
 *  - Reusable: '''Yes'''; all state directly associated with the analyzed code block is
 *              reset by the method `initProperties`.
 *  - No Partial Results: If the abstract interpretation was aborted the results have
 *              no meaning and must not be used; however, if the abstract interpretation
 *              is later continued and successfully completed the results are correct.
 *
 * @author Michael Eichberg
 * @author Marc Eichler
 */
trait RecordCFG
    extends CoreDomainFunctionality
    with CustomInitialization
    with ai.ReturnInstructionsDomain {
    cfgDomain: ValuesDomain with TheCode ⇒

    //
    // DIRECTLY RECORDED INFORMATION
    //

    // ... elements are either null or non-empty
    private[this] var regularSuccessors: Array[IntArraySet] = _

    // ... elements are either null or non-empty
    private[this] var exceptionHandlerSuccessors: Array[IntArraySet] = _

    private[this] var theExitPCs: mutable.BitSet = _ // IMPROVE use an Int(Trie)Set

    private[this] var theSubroutineStartPCs: IntArraySet = _

    /**
     * The set of nodes to which a(n un)conditional jump back is executed.
     */
    private[this] var theJumpBackTargetPCs: IntArraySet = _

    //
    // DERIVED INFORMATION
    //

    /**
     * @note    We use the monitor associated with "this" when computing predecessors;
     *          the monitor associated with "this" is not (to be) used otherwise!
     */
    private[this] var thePredecessors: SRef[Array[IntArraySet]] = _ // uses regularSuccessors as lock

    /**
     * @note    We use the monitor associated with regularSuccessors when computing the dominator
     *          tree; the monitor associated with regularSuccessors is not (to be) used otherwise!
     */
    private[this] var theDominatorTree: SRef[DominatorTree] = _

    /**
     * @note    We use the monitor associated with theJumpBackTargetPCs when computing the post
     *          dominator tree; the monitor associated with theJumpBackTargetPCs is not (to be)
     *          used otherwise!
     */
    private[this] var thePostDominatorTree: SRef[PostDominatorTree] = _

    private[this] var theControlDependencies: SRef[ControlDependencies] = _

    /**
     * @note    We use the monitor associated with exceptionHandlerSuccessors when computing the
     *          bb based cfg; the monitor associated with exceptionHandlerSuccessors is not (to be)
     *          used otherwise!
     */
    private[this] var theBBCFG: SRef[CFG] = _

    //
    // METHODS WHICH RECORD THE AI TIME CFG AND WHICH ARE CALLED BY THE FRAMEWORK
    //

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def initProperties(
        code:          Code,
        cfJoins:       BitSet,
        initialLocals: Locals
    ): Unit = {
        val codeSize = code.instructions.length
        regularSuccessors = new Array[IntArraySet](codeSize)
        exceptionHandlerSuccessors = new Array[IntArraySet](codeSize)
        theExitPCs = new mutable.BitSet(codeSize)
        theSubroutineStartPCs = IntArraySet.empty
        theJumpBackTargetPCs = IntArraySet.empty

        // The following values are initialized lazily (when required); after the abstract
        // interpretation was (successfully) performed!
        thePredecessors = null
        theBBCFG = null
        theDominatorTree = null
        thePostDominatorTree = null
        theControlDependencies = null

        super.initProperties(code, cfJoins, initialLocals)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def flow(
        currentPC:                        PC,
        currentOperands:                  Operands,
        currentLocals:                    Locals,
        successorPC:                      PC,
        isSuccessorScheduled:             Answer,
        isExceptionalControlFlow:         Boolean,
        abruptSubroutineTerminationCount: Int,
        wasJoinPerformed:                 Boolean,
        worklist:                         List[PC],
        operandsArray:                    OperandsArray,
        localsArray:                      LocalsArray,
        tracer:                           Option[AITracer]
    ): List[PC] = {

        if (successorPC <= currentPC) { // "<=" to handle "x: goto x"
            theJumpBackTargetPCs += successorPC
        }

        val successors =
            if (isExceptionalControlFlow)
                cfgDomain.exceptionHandlerSuccessors
            else
                cfgDomain.regularSuccessors

        val successorsOfPC = successors(currentPC)
        if (successorsOfPC eq null)
            successors(currentPC) = new IntArraySet1(successorPC)
        else {
            val newSuccessorsOfPC = successorsOfPC + successorPC
            if (newSuccessorsOfPC ne successorsOfPC) successors(currentPC) = newSuccessorsOfPC
        }

        super.flow(
            currentPC, currentOperands, currentLocals,
            successorPC, isSuccessorScheduled,
            isExceptionalControlFlow, abruptSubroutineTerminationCount,
            wasJoinPerformed,
            worklist,
            operandsArray, localsArray,
            tracer
        )
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def jumpToSubroutine(pc: PC, branchTarget: PC, returnTarget: PC): Unit = {
        theSubroutineStartPCs += branchTarget
        super.jumpToSubroutine(pc, branchTarget, returnTarget)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def returnVoid(pc: PC): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.returnVoid(pc)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def ireturn(
        pc:    PC,
        value: DomainValue
    ): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.ireturn(pc, value)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def lreturn(
        pc:    PC,
        value: DomainValue
    ): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.lreturn(pc, value)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def freturn(
        pc:    PC,
        value: DomainValue
    ): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.freturn(pc, value)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def dreturn(
        pc:    PC,
        value: DomainValue
    ): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.dreturn(pc, value)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def areturn(
        pc:    PC,
        value: DomainValue
    ): Computation[Nothing, ExceptionValue] = {
        theExitPCs += pc
        super.areturn(pc, value)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def abruptMethodExecution(
        pc:             PC,
        exceptionValue: ExceptionValue
    ): Unit = {
        theExitPCs += pc
        super.abruptMethodExecution(pc, exceptionValue)
    }

    /**
     * @inheritdoc
     *
     * @note If another domain always overrides this method the invocation of this one has to be
     *       ensured; otherwise the recorded CFG will be incomplete.
     */
    abstract override def abstractInterpretationEnded(
        aiResult: AIResult { val domain: cfgDomain.type }
    ): Unit = {
        super.abstractInterpretationEnded(aiResult)

        assert(exceptionHandlerSuccessors.forall(s ⇒ (s eq null) || s.nonEmpty))
        assert(regularSuccessors.forall(s ⇒ (s eq null) || s.nonEmpty))
    }

    // ==================================== BASIC QUERIES ==========================================
    //
    //

    /**
     * Returns all PCs that may lead to the (ab)normal termination of the method. I.e.,
     * those instructions (in particular method call instructions, but potentially also
     * array access instructions and (I]L)DIV|MOD instructions etc.) that may throw
     * some unhandled exceptions will also be returned; even if the instruction may
     * also have regular and also exception handlers!
     */
    def exitPCs: BitSet = theExitPCs

    /**
     * Returns the PCs of the first instructions of all subroutines; that is, the instructions
     * a `JSR` instruction jumps to.
     */
    def subroutineStartPCs: PCs = theSubroutineStartPCs

    /**
     * The set of instructions to which a jump back is performed.
     */
    def jumpBackTargetPCs: IntArraySet = theJumpBackTargetPCs

    // IMPROVE Move the functionality to record/decide which instructions were executed to another domain which uses the operandsArray as the source of the information (more efficient!)
    /**
     * Returns `true` if the instruction with the given `pc` was executed.
     * The `pc` has to identify a valid instruction.
     */
    private[this] final def unsafeWasExecuted(pc: PC): Boolean = {
        (regularSuccessors(pc) ne null) || (exceptionHandlerSuccessors(pc) ne null) ||
            theExitPCs.contains(pc)
    }

    /**
     * Returns `true` if the instruction with the given `pc` was executed.
     */
    final def wasExecuted(pc: PC): Boolean = pc < code.instructions.length && unsafeWasExecuted(pc)

    /**
     * Computes the set of all executed instructions.
     */
    final def allExecuted: BitSet = {
        val wasExecuted = new mutable.BitSet(code.instructions.length)
        code.programCounters.foreach { pc ⇒
            if (unsafeWasExecuted(pc))
                wasExecuted += pc
        }
        wasExecuted
    }

    /**
     * Returns the program counter(s) of the instruction(s) that is(are) executed next if
     * the evaluation of this instruction may succeed without raising an exception.
     *
     * The returned set is always empty for `return` instructions. It is also empty for
     * instructions that always throw an exception (e.g., an integer value that is divided
     * by zero will always result in a NullPointException.)
     *
     * @note The [[org.opalj.br.instructions.ATHROW]] instruction will never have a
     *      `regularSuccessor`. The `return` instructions will never have any successors.
     */
    def regularSuccessorsOf(pc: PC): PCs = {
        val s = regularSuccessors(pc)
        if (s ne null) s else NoPCs
    }

    final def hasMultipleSuccessors(pc: PC): Boolean = {
        val regularSuccessorsCount = regularSuccessorsOf(pc).size
        regularSuccessorsCount > 1 ||
            (regularSuccessorsCount + exceptionHandlerSuccessorsOf(pc).size) > 1
    }

    def isDirectRegularPredecessorOf(pc: PC, successorPC: PC): Boolean = {
        regularSuccessorsOf(pc).contains(successorPC)
    }

    /**
     * Returns the set of all instructions executed after the instruction with the
     * given `pc`. If this set is empty, either the instruction belongs to dead code,
     * the instruction is a `return` instruction or the `instruction` throws an exception
     * that is never handled internally.
     *
     * @note The set is recalculated on demand.
     */
    def allSuccessorsOf(pc: PC): PCs = {
        regularSuccessorsOf(pc) ++ exceptionHandlerSuccessorsOf(pc)
    }

    final def successorsOf(pc: PC, regularSuccessorOnly: Boolean): PCs = {
        if (regularSuccessorOnly)
            regularSuccessorsOf(pc)
        else
            allSuccessorsOf(pc)
    }

    def hasNoSuccessor(pc: PC): Boolean = {
        (regularSuccessors(pc) eq null) && (exceptionHandlerSuccessors eq null)
    }

    /**
     * Returns `true` if the execution of the given instruction – identified by its pc –
     * ex-/implicitly throws an exception that is (potentially) handled by the method.
     */
    def throwsException(pc: PC): Boolean = exceptionHandlerSuccessors(pc) ne null

    /**
     * Returns `true` if the execution of the given instruction – identified by its pc –
     * '''always just''' throws an exception that is (potentially) handled by the method.
     */
    def justThrowsException(pc: PC): Boolean = {
        (exceptionHandlerSuccessors(pc) ne null) && (regularSuccessors(pc) eq null)
    }

    def foreachSuccessorOf(pc: PC)(f: PC ⇒ Unit): Unit = {
        regularSuccessorsOf(pc).foreach { f }
        exceptionHandlerSuccessorsOf(pc).foreach { f }
    }

    /**
     * Tests if the instruction with the given `pc` has a successor instruction with
     * a `pc'` that satisfies the given predicate `p`.
     */
    def hasSuccessor(
        pc:                    PC,
        regularSuccessorsOnly: Boolean,
        p:                     PC ⇒ Boolean
    ): Boolean = {
        var visitedSuccessors: IntArraySet = new IntArraySet1(pc) // IMPROVE Use IntArraySetBuilder?
        var successorsToVisit = successorsOf(pc, regularSuccessorsOnly)
        while (successorsToVisit.nonEmpty) {
            if (successorsToVisit.exists { succPC ⇒ p(succPC) })
                return true;

            visitedSuccessors ++= successorsToVisit
            successorsToVisit =
                successorsToVisit.foldLeft(IntArraySet.empty) { (l, r) ⇒
                    l ++ (
                        successorsOf(r, regularSuccessorsOnly) withFilter { pc ⇒
                            !visitedSuccessors.contains(pc)
                        }
                    )
                }
        }
        false
    }

    /**
     * Tests if the instruction with the given pc is a direct or
     * indirect predecessor of the given successor instruction.
     *
     * If `pc` equals `successorPC` `true` is returned.
     *
     * @note This method will traverse the entire graph if `successorPC` is '''not''' a regular
     *       predecessor of `pc`. Hence, consider using the `(Post)DominatorTree`.
     */
    def isRegularPredecessorOf(pc: PC, successorPC: PC): Boolean = {
        if (pc == successorPC)
            return true;

        // IMPROVE  Use a better data-structure; e.g., an IntTrieSet with efficient head and tail operations to avoid that the successorsToVisit contains the same value multiple times
        var visitedSuccessors = Set(pc)
        val successorsToVisit = IntArrayStack.fromSeq(regularSuccessorsOf(pc).iterator)
        while (successorsToVisit.nonEmpty) {
            val nextPC = successorsToVisit.pop()
            if (nextPC == successorPC)
                return true;

            visitedSuccessors += nextPC
            regularSuccessorsOf(nextPC).foreach { nextSuccessor ⇒
                if (!visitedSuccessors.contains(nextSuccessor))
                    successorsToVisit.push(nextSuccessor)
            }
        }
        false
    }

    /**
     * Returns the program counter(s) of the instruction(s) that is(are) executed next if
     * the evaluation of this instruction may raise an exception.
     *
     * The returned set is always empty for instructions that cannot raise exceptions,
     * such as the `StackManagementInstruction`s.
     *
     * @note    The [[org.opalj.br.instructions.ATHROW]] has successors if and only if the
     *          thrown exception is directly handled inside this code block.
     * @note    The successor instructions are necessarily the handlers of catch blocks.
     */
    def exceptionHandlerSuccessorsOf(pc: PC): PCs = {
        val s = exceptionHandlerSuccessors(pc)
        if (s ne null) s else NoPCs
    }

    /**
     * Returns `true` if the exception handler may handle at least one exception thrown
     * by an instruction in its try block.
     */
    final def handlesException(exceptionHandler: ExceptionHandler): Boolean = {
        val endPC = exceptionHandler.endPC
        val handlerPC = exceptionHandler.handlerPC
        var currentPC = exceptionHandler.startPC
        val code = this.code
        while (currentPC <= endPC) {
            if (exceptionHandlerSuccessorsOf(currentPC).exists(_ == handlerPC))
                return true;
            currentPC = code.pcOfNextInstruction(currentPC)
        }
        false
    }

    /**
     * Computes the transitive hull of all instructions reachable from the given set of
     * instructions.
     */
    def allReachable(pcs: IntArraySet): IntArraySet = {
        pcs.foldLeft(IntArraySet.empty) { (c, pc) ⇒ c ++ allReachable(pc) }
    }

    /**
     * Computes the transitive hull of all instructions reachable from the given instruction.
     */
    def allReachable(pc: PC): IntArraySet = {
        var allReachable: IntArraySet = new IntArraySet1(pc)
        var successorsToVisit = allSuccessorsOf(pc)
        while (successorsToVisit.nonEmpty) {
            val (succPC, newSuccessorsToVisit) = successorsToVisit.getAndRemove
            successorsToVisit = newSuccessorsToVisit
            if (!allReachable.contains(succPC)) {
                allReachable += succPC
                successorsToVisit ++= allSuccessorsOf(succPC)
            }
        }
        allReachable
    }

    // ==================== METHODS WHICH COMPUTE DERIVED DATA-STRUCTURES ==========================
    // ===================== OR WHICH OPERATE ON DERIVED DATA-STRUCTURES ===========================
    //
    //

    private[this] def getOrInitField[T >: Null <: AnyRef](
        getFieldValue: () ⇒ SRef[T], // executed concurrently
        setFieldValue: SRef[T] ⇒ Unit, // never executed concurrently
        lock:          AnyRef
    )(
        computeFieldValue: ⇒ T // never executed concurrently
    ): T = {
        val ref = getFieldValue()
        if (ref eq null) {
            lock.synchronized {
                val ref = getFieldValue()
                var f: T = null
                if ((ref eq null) || { f = ref.get(); f eq null }) {
                    val newValue = computeFieldValue
                    setFieldValue(new SRef(newValue))
                    newValue
                } else {
                    f // initialized by a side-effect of evaluating the if condition
                }
            }
        } else {
            val f = ref.get()
            if (f eq null) {
                lock.synchronized {
                    val ref = getFieldValue()
                    var f: T = null
                    if ((ref eq null) || { f = ref.get(); f eq null }) {
                        val newValue = computeFieldValue
                        setFieldValue(new SRef(newValue))
                        newValue
                    } else {
                        f // initialized by a side-effect of evaluating the if condition
                    }
                }
            } else {
                f // best case... already computed and still available
            }
        }
    }

    private[this] def predecessors: Array[IntArraySet] = {
        getOrInitField[Array[IntArraySet]](
            () ⇒ this.thePredecessors,
            (predecessors) ⇒ this.thePredecessors = predecessors, // to cache the result
            this
        ) {
                val predecessors = new Array[IntArraySet](regularSuccessors.length)
                for {
                    pc ← code.programCounters
                    successorPC ← allSuccessorsOf(pc)
                } {
                    val oldPredecessorsOfSuccessor = predecessors(successorPC)
                    predecessors(successorPC) =
                        if (oldPredecessorsOfSuccessor eq null) {
                            new IntArraySet1(pc)
                        } else {
                            oldPredecessorsOfSuccessor + pc
                        }

                }
                predecessors
            }
    }

    /**
     * Returns the program counter(s) of the instruction(s) that is(are) executed
     * before the instruction with the given pc.
     *
     * If the instruction with the given `pc` was never executed an empty set is returned.
     *
     * @param pc A valid program counter.
     */
    def predecessorsOf(pc: PC): PCs = {
        val s = predecessors(pc)
        if (s ne null) s else NoPCs
    }

    /**
     * Returns `true` if the instruction with the given pc has multiple direct
     * predecessors (more than one).
     */
    final def hasMultiplePredecessors(pc: PC): Boolean = predecessorsOf(pc).size > 1

    final def foreachPredecessorOf(pc: PC)(f: PC ⇒ Unit): Unit = predecessorsOf(pc).foreach(f)

    /**
     * Returns the dominator tree.
     *
     * @note   To get the list of all evaluated instructions and their dominators.
     *         {{{
     *         val result = AI(...,...,...)
     *         val evaluated = result.evaluatedInstructions
     *         }}}
     */
    def dominatorTree: DominatorTree = {
        getOrInitField[DominatorTree](
            () ⇒ this.theDominatorTree,
            (dt) ⇒ this.theDominatorTree = dt,
            regularSuccessors
        ) {
                // We want to keep a non-soft reference and avoid any further useless synchronization.
                val predecessors = this.predecessors
                def foreachPredecessorOf(pc: PC)(f: PC ⇒ Unit): Unit = {
                    val s = predecessors(pc)
                    if (s ne null)
                        s.foreach(f)
                }

                DominatorTree(
                    startNode = 0,
                    startNodeHasPredecessors = predecessorsOf(0).nonEmpty,
                    foreachSuccessorOf,
                    foreachPredecessorOf,
                    maxNode = code.instructions.length - 1
                )
            }
    }

    /**
     * Returns the first instructions of the infinite loops of the current method. An infinite loop
     * is a sequence of instructions that does not have a connection to any exit node. The very
     * vast majority of methods does not have infinite loops.
     */
    def infiniteLoopHeaders: IntArraySet = {
        if (theJumpBackTargetPCs.isEmpty)
            return IntArraySet.empty;
        // Let's test if the set of nodes reachable from a potential loop header is
        // closed; i.e., does not include an exit node and does not refer to a node
        // which is outside of the loop.

        /*
        // The nodes which are connected to an exit node... if not, the loop eagerly
        // aborts the computation and it doesn't matter that it contains "wrong data"
        // w.r.t. the last analyzed loop.
        val allLoopsTerminate =
            thePotentialLoopHeaders forall { loopHeaderPC ⇒
                val visitedNodes = new mutable.BitSet(code.instructions.length)
                var nodesToVisit = List(loopHeaderPC)
                var isInfiniteLoop = true
                while (nodesToVisit.nonEmpty) {
                    val nextPC = nodesToVisit.head
                    if (theExitPCs.contains(nextPC)) {
                        isInfiniteLoop = false
                        nodesToVisit = Nil // terminate while loop
                    } else {
                        nodesToVisit = nodesToVisit.tail
                        if (!visitedNodes.contains(nextPC)) {
                            visitedNodes += nextPC
                            nodesToVisit =
                                regularSuccessorsOf(nextPC).foldLeft(nodesToVisit){
                                    (c, n) ⇒ n :&: c
                                }
                            nodesToVisit =
                                exceptionHandlerSuccessorsOf(nextPC).foldLeft(nodesToVisit){
                                    (c, n) ⇒ n :&: c
                                }
                        }
                    }
                }
                !isInfiniteLoop
            }
        !allLoopsTerminate
        */

        // IDEA traverse the cfg from the exit nodes to the start node and try to determine if
        // every loop header can be reached.
        val predecessors = this.predecessors
        var remainingPotentialLoopHeaders = theJumpBackTargetPCs
        var nodesToVisit = theExitPCs.foldLeft(Nil: List[Int])((c, n) ⇒ n :&: c)
        val visitedNodes = new mutable.BitSet(code.codeSize)
        while (remainingPotentialLoopHeaders.nonEmpty && nodesToVisit.nonEmpty) {
            val nextPC = nodesToVisit.head
            nodesToVisit = nodesToVisit.tail
            visitedNodes += nextPC
            val nextPredecessors = predecessors(nextPC)
            if (nextPredecessors ne null) {
                nextPredecessors.foreach { predPC ⇒
                    remainingPotentialLoopHeaders -= predPC
                    if (!visitedNodes.contains(predPC)) {
                        nodesToVisit :&:= predPC
                    }
                }
            }
        }
        remainingPotentialLoopHeaders
    }

    /**
     * Returns the [[PostDominatorTree]] (PDT).
     *
     * The PDT is computed using the reverse control-flow graph using a single explicit, virtual
     * exit node which is – in case of true infinite loops – also connected with the first
     * instructions of the infinite loops such that the computation of control-dependencies using
     * the PDT is facilitated.
     */
    def postDominatorTree: PostDominatorTree = {
        getOrInitField[PostDominatorTree](
            () ⇒ this.thePostDominatorTree,
            (pdt) ⇒ this.thePostDominatorTree = pdt,
            theJumpBackTargetPCs
        ) {
                val exitPCs = theExitPCs

                // We want to keep a non-soft reference and avoid any further useless synchronization.
                val predecessors = this.predecessors
                def foreachPredecessorOf(pc: PC)(f: PC ⇒ Unit): Unit = {
                    val s = predecessors(pc)
                    if (s ne null)
                        s.foreach(f)
                }

                // The headers of infinite loops are used as additional exit nodes;
                // this enables use to compute meaninful dependency information for
                // the loops body; however, we need to clean up the control dependency
                // information w.r.t. the loop headers afterwards...
                // In general, the first instruction of an infinite loop can be any
                // kind of instruction (e.g., also an if instruction on which
                // instructions from the loop body are control-dependent on.)
                val infiniteLoopHeaders = this.infiniteLoopHeaders
                if (infiniteLoopHeaders.nonEmpty) {
                    val exitNodes = exitPCs.foldLeft(infiniteLoopHeaders)(_ + _)
                    PostDominatorTree(
                        exitNodes.contains,
                        infiniteLoopHeaders,
                        exitNodes.foreach,
                        foreachSuccessorOf,
                        foreachPredecessorOf,
                        maxNode = code.instructions.length - 1
                    )
                } else {
                    PostDominatorTree(
                        exitPCs.contains,
                        IntArraySet.empty,
                        exitPCs.foreach,
                        foreachSuccessorOf,
                        foreachPredecessorOf,
                        maxNode = code.instructions.length - 1
                    )
                }
            }
    }

    /**
     * Returns the control dependencies graph.
     *
     * Internally, a post dominator tree is used to compute the dominance frontiers, but that
     * post dominator tree (PDT) is (in general) an augmented PDT because it may contain additional
     * edges if the underlying method has inifinite loops. (Here, an infinite loop must never lead
     * to a(n ab)normal return from the method.)
     */
    def controlDependencies: ControlDependencies = {
        getOrInitField[ControlDependencies](
            () ⇒ this.theControlDependencies,
            (cd) ⇒ this.theControlDependencies = cd,
            theJumpBackTargetPCs
        ) { new ControlDependencies(DominanceFrontiers(postDominatorTree, wasExecuted)) }
    }

    def bbCFG: CFG = {
        getOrInitField[CFG](() ⇒ theBBCFG, (cfg) ⇒ theBBCFG = cfg, exceptionHandlerSuccessors) {
            computeBBCFG
        }
    }

    /**
     * Returns the basic block based representation of the cfg. This CFG may have less nodes
     * than the CFG computed using the naive bytecode representation because it was possible
     * (a) to detect dead paths or (b) to identify that a method call may never throw an exception
     * (in the given situation).
     */
    private[this] def computeBBCFG: CFG = {

        val instructions = code.instructions
        val codeSize = instructions.length

        val normalReturnNode = new ExitNode(normalReturn = true)
        val abnormalReturnNode = new ExitNode(normalReturn = false)

        // 1. basic initialization
        // BBs is a sparse array; only those fields are used that are related to an instruction
        // that was actually executed!
        val bbs = new Array[BasicBlock](codeSize)

        val exceptionHandlers = mutable.HashMap.empty[PC, CatchNode]
        for {
            (exceptionHandler, index) ← code.exceptionHandlers.iterator.zipWithIndex
            // 1.1.    Let's check if the handler was executed at all.
            if unsafeWasExecuted(exceptionHandler.handlerPC)
            // 1.2.    The handler may be shared by multiple try blocks, hence, we have
            //         to ensure the we have at least one instruction in the try block
            //         that jumps to the handler.
            if handlesException(exceptionHandler)
        } {
            val handlerPC = exceptionHandler.handlerPC
            val catchNodeCandiate = new CatchNode(exceptionHandler, index)
            val catchNode = exceptionHandlers.getOrElseUpdate(handlerPC, catchNodeCandiate)
            var handlerBB = bbs(handlerPC)
            if (handlerBB eq null) {
                handlerBB = new BasicBlock(handlerPC)
                handlerBB.addPredecessor(catchNode)
                bbs(handlerPC) = handlerBB
            } else {
                handlerBB.addPredecessor(catchNode)
            }
            catchNode.addSuccessor(handlerBB)
        }

        // 2. iterate over the code to determine the basic block boundaries
        var runningBB: BasicBlock = null
        val pcIt = code.programCounters
        while (pcIt.hasNext) {
            val pc = pcIt.next
            if (runningBB eq null) {
                runningBB = bbs(pc)
                if (runningBB eq null) {
                    if (unsafeWasExecuted(pc)) {
                        runningBB = new BasicBlock(pc)
                        bbs(pc) = runningBB
                    } else {
                        // When we reach this point, we have found code that is
                        // dead in the sense that it is not reachable on any
                        // possible control-flow. Such code is typically not
                        // generated by mature compilers, but some compilers,
                        // e.g., the Groovy compiler, are known to produce some
                        // very bad code!
                    }
                }
            }
            if (runningBB ne null) {
                var endRunningBB: Boolean = false
                var connectedWithNextBBs = false

                if (theExitPCs.contains(pc)) {
                    val successorNode = code.instructions(pc) match {
                        case r: ReturnInstruction ⇒ normalReturnNode
                        case _                    ⇒ abnormalReturnNode
                    }
                    runningBB.addSuccessor(successorNode)
                    successorNode.addPredecessor(runningBB)
                    endRunningBB = true
                    // connection is done later, when we handle the (regular) successors
                }

                // NOTE THAT WE NEVER HAVE TO SPLIT A BLOCK, BECAUSE WE IMMEDIATELY CONSIDER ALL
                // INCOMING AND OUTGOING DEPENDENCIES!
                def connect(sourceBB: BasicBlock, targetBBStartPC: PC): Unit = {
                    var targetBB = bbs(targetBBStartPC)
                    if (targetBB eq null) {
                        targetBB = new BasicBlock(targetBBStartPC)
                        bbs(targetBBStartPC) = targetBB
                    }
                    targetBB.addPredecessor(sourceBB)
                    sourceBB.addSuccessor(targetBB)
                }

                val nextInstructionPC = code.pcOfNextInstruction(pc)
                val theRegularSuccessors = regularSuccessors(pc)
                if (theRegularSuccessors eq null) {
                    endRunningBB = true
                } else {
                    // ... also handles the case where the last instruction is, e.g., a goto
                    if (endRunningBB || theRegularSuccessors.exists(_ != nextInstructionPC)) {
                        theRegularSuccessors.foreach { targetPC ⇒ connect(runningBB, targetPC) }
                        endRunningBB = true
                        connectedWithNextBBs = true
                    }
                }

                val theExceptionHandlerSuccessors = exceptionHandlerSuccessorsOf(pc)
                if (theExceptionHandlerSuccessors.nonEmpty) {
                    if (!endRunningBB && !connectedWithNextBBs) {
                        connect(runningBB, nextInstructionPC)
                        connectedWithNextBBs = true
                    }
                    endRunningBB = true
                    theExceptionHandlerSuccessors.foreach { handlerPC ⇒
                        val catchNode: CatchNode = exceptionHandlers(handlerPC)
                        catchNode.addPredecessor(runningBB)
                        runningBB.addSuccessor(catchNode)
                    }
                }
                if (!endRunningBB &&
                    !connectedWithNextBBs &&
                    hasMultiplePredecessors(nextInstructionPC)) {
                    endRunningBB = true
                    connect(runningBB, nextInstructionPC)
                }

                if (endRunningBB) {
                    runningBB.endPC = pc
                    runningBB = null
                } else {
                    bbs(nextInstructionPC) = runningBB
                }
            }
        }

        if (theSubroutineStartPCs.nonEmpty) {
            theSubroutineStartPCs.foreach { pc ⇒ bbs(pc).setIsStartOfSubroutine() }
        }

        // 3. create CFG class
        CFG(code, normalReturnNode, abnormalReturnNode, exceptionHandlers.values.toList, bbs)
    }

    // ================================== GENERAL HELPER METHODS ===================================
    //
    //
    //

    /**
     * Creates a graph representation of the CFG.
     *
     * @note The returned graph is recomputed whenever this method is called.
     * @note This implementation is for debugging purposes only. It is NOT performance optimized!
     */
    def cfgAsGraph(): DefaultMutableNode[List[PC]] = {
        import scala.collection.immutable.{List ⇒ ScalaList}
        val instructions = code.instructions
        val codeSize = instructions.length
        val nodes = new Array[DefaultMutableNode[List[PC]]](codeSize)
        val nodePredecessorsCount = new Array[Int](codeSize)
        // 1. create nodes
        val exitNode = new DefaultMutableNode[List[PC]](
            Nil,
            (n) ⇒ "Exit",
            Map(
                "shape" → "doubleoctagon",
                "fillcolor" → "black",
                "color" → "white",
                "labelloc" → "l"
            ),
            ScalaList.empty[DefaultMutableNode[List[PC]]]
        )
        for (pc ← code.programCounters) {
            nodes(pc) = {
                var visualProperties = Map("shape" → "box", "labelloc" → "l")

                if (instructions(pc).isInstanceOf[ReturnInstruction]) {
                    visualProperties += "fillcolor" → "green"
                    visualProperties += "style" → "filled"
                } else if (instructions(pc).isInstanceOf[ATHROW.type]) {
                    if (theExitPCs.contains(pc)) {
                        visualProperties += "fillcolor" → "red"
                        visualProperties += "style" → "filled"
                    } else {
                        visualProperties += "fillcolor" → "yellow"
                        visualProperties += "style" → "filled"
                    }
                } else if (allSuccessorsOf(pc).isEmpty && !theExitPCs.contains(pc)) {
                    visualProperties += "fillcolor" → "red"
                    visualProperties += "style" → "filled"
                    visualProperties += "shape" → "octagon"
                }

                if (code.exceptionHandlersFor(pc).nonEmpty) {
                    visualProperties += "color" → "orange"
                }

                if (code.exceptionHandlers.exists { eh ⇒ eh.handlerPC == pc }) {
                    visualProperties += "peripheries" → "2"
                }

                def pcsToString(pcs: List[PC]): String = {
                    def pcToString(pc: PC): String = {
                        val ln = code.lineNumber(pc).map(ln ⇒ s"[ln=$ln]").getOrElse("")
                        pc + ln+": "+cfgDomain.code.instructions(pc).toString(pc)
                    }
                    pcs.map(pcToString).mkString("", "\\l\\l", "\\l")
                }

                new DefaultMutableNode(
                    List(pc),
                    pcsToString,
                    visualProperties,
                    ScalaList.empty[DefaultMutableNode[List[PC]]]
                )
            }
        }
        // 2. create edges
        for (pc ← code.programCounters) {
            for (succPC ← allSuccessorsOf(pc)) {
                nodes(pc).addChild(nodes(succPC))
                nodePredecessorsCount(succPC) += 1
            }
            if (theExitPCs.contains(pc)) {
                nodes(pc).addChild(exitNode)
            }
        }

        // 3. fold nodes
        // Nodes that have only one successor and where the successor has only one
        // predecessor are merged into one node; basically, we recreate the
        // _effective_ basic blocks; an _effective_ basic block is a block where we do
        // _not observe_ any jumps in and out unless we are at the beginning or end of
        // the block
        for (pc ← code.programCounters) {
            val currentNode = nodes(pc)
            if (currentNode.hasOneChild) {
                val successorNode = currentNode.firstChild
                if (successorNode ne exitNode) {
                    val successorNodePC = successorNode.identifier.head
                    if (nodePredecessorsCount(successorNodePC) == 1) {
                        currentNode.updateIdentifier(
                            currentNode.identifier :&:: currentNode.firstChild.identifier
                        )
                        currentNode.mergeVisualProperties(successorNode.visualProperties)
                        currentNode.removeLastAddedChild() // the only child...
                        currentNode.addChildren(successorNode.children)
                        nodes(successorNodePC) = currentNode
                    }
                }
            }
        }

        nodes(0)
    }
}
