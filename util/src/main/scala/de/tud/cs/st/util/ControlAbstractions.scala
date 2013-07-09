/* License (BSD Style License):
 * Copyright (c) 2009 - 2013
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
 *  - Neither the name of the Software Technology Group or Technische
 *    Universität Darmstadt nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
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
package de.tud.cs.st
package util

import language.experimental.macros

import reflect.ClassTag
import reflect.macros.Context
import reflect.api.Trees

import java.io.InputStream

/**
 * Defines generally useful control abstractions.
 *
 * @author Michael Eichberg
 */
object ControlAbstractions {

    /**
     * This function takes care of the correct handling of input streams.
     * The function takes a function <code>f</code> that creates a new <code>InputStream</code> (f is a
     * named parameter) and a function <code>r</code> that processes an input stream. When `r` has
     * finished processing the input stream, the stream is closed.
     * If f should return <code>null</code>, <code>null</code> is passed to r.
     */
    @throws[java.io.IOException]
    def process[I <: InputStream, T](f: ⇒ I)(r: I ⇒ T): T = {
        val in = f
        try {
            r(in)
        } finally {
            if (in != null) in.close
        }
    }

    def withResource[I <: java.io.Closeable, O](r: ⇒ I)(f: I ⇒ O): O = {
        try {
            f(r)
        } finally {
            r.close
        }
    }

    def repeatToArray[T: ClassTag](times: Int)(f: ⇒ T): Array[T] = {
        val array = new Array[T](times)
        var i = 0
        while (i < times) {
            array(i) = f
            i += 1
        }
        array
    }

    /**
     * Evaluates the given expression `f` with type `T` the given number of `times` and stores the
     * result in an `IndexedSeq[T]`.
     *
     * ==Example Usage==
     * {{{
     * val result = repeat(15) {
     *    System.in.read()
     * }
     * }}}
     *
     * @param times The number of times the expression `f` is evaluated. This expression is evaluated
     *     exactly once.
     * @param f An expression that is evaluated the given number of times unless an exception is
     *     thrown. Hence, even though f is not a by-name parameter, it behaves in the same way.
     * @return The result of the evaluation of the expression `f` the given number of times. If `times` is
     *     zero an empty sequence is returned.
     */
    def repeat[T](times: Int)(f: T): IndexedSeq[T] = macro ControlAbstractionsImplementation.repeat[T]
    // OLD IMPLEMENTATION USING HIGHER-ORDER FUNCTIONS
    // (DO NOT DELETE - TO DOCUMENT THE DESIGN DECISION FOR MACROS)
    //        def repeat[T](times: Int)(f: ⇒ T): IndexedSeq[T] = {
    //            val array = new scala.collection.mutable.ArrayBuffer[T](times)
    //            var i = 0
    //            while (i < times) {
    //                array += f
    //                i += 1
    //            }
    //            array
    //        }
    // The macro-based implementation has proven to be approx. 1,3 to 1,4 times faster when
    // the number of times that we repeat an operation (5 to 15 times) is small (which is generally the case
    // when we read in Java class files)

}

object ControlAbstractionsImplementation {

    def repeat[T: c.WeakTypeTag](c: Context)(times: c.Expr[Int])(f: c.Expr[T]): c.Expr[IndexedSeq[T]] = {
        import c.universe._

        reify {
            val size = times.splice // we must not evaluate the expression more than once!
            if (size == 0) {
                IndexedSeq.empty
            } else {
                val array = new scala.collection.mutable.ArrayBuffer[T](size)
                var i = 0
                while (i < size) {
                    val value = f.splice // we evaluate the expression the given number of times
                    array += value
                    i += 1
                }
                array
            }
        }
    }
}

