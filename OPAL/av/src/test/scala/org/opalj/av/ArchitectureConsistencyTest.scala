/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2014
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
package org.opalj.av
package checking

import org.junit.runner.RunWith

import org.opalj.bi.TestSupport.locateTestResources
import org.opalj.br.{BooleanValue, StringValue}
import org.opalj.br.reader.Java8Framework.ClassFiles

import org.scalatest._
import org.scalatest.junit.JUnitRunner

/**
 * Tests for architectural Specifications.
 *
 * The architecture is defined w.r.t. the "Mathematics test classes".
 *
 * @author Samuel Beracasa
 * @author Marco Torsello
 */
@RunWith(classOf[JUnitRunner])
class ArchitectureConsistencyTest extends FlatSpec with Matchers with BeforeAndAfterAll {

    val project = ClassFiles(locateTestResources("classfiles/mathematics.jar", "av"))

    def testEnsemblesAreNonEmpty(specification: Specification): Unit = {
        specification.ensembles.foreach { e ⇒
            val (ensembleID, (matcher, extent)) = e
            if (ensembleID != 'empty && extent.isEmpty)
                fail(ensembleID+" didn't match any elements ("+matcher+")")
        }
    }

    behavior of "the Architecture Validation Framework when checking architectural dependencies"

    /*
     * outgoing is_only_allowed_to constraint validations
     */
    it should "correctly validate a valid specification using is_only_allowed_to_use constraints" in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Example is_only_allowed_to (USE, 'Mathematics)
        }

        val result = specification.analyze().map(_.toString).toSeq.sorted.mkString("\n")
        result should be(empty) // <= primary test

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("correctly identify deviations between the specified (using is_only_allowed_to_use constraints) and implemented architecture") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Mathematics is_only_allowed_to (USE, 'Rational)
        }
        specification.analyze() should not be (empty) // <= primary test

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * outgoing is_not_allowed_to constraint validation
     */
    it should ("validate the is_not_allowed_to_use constraint with no violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Example is_not_allowed_to (USE, 'Rational)
        }
        val result = specification.analyze().map(_.toString).toSeq.sorted.mkString("\n")
        result should be(empty)

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("validate the is_not_allowed_to_use constraint with violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Mathematics is_not_allowed_to (USE, 'Number)
        }
        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * incoming is_only_to_be_used_by constraint validation
     */
    it should ("validate the is_only_to_be_used_by constraint with no violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Mathematics is_only_to_be_used_by 'Example
        }
        val result = specification.analyze().map(_.toString).toSeq.sorted.mkString("\n")
        result should be(empty)

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("validate the is_only_to_be_used_by constraint with violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Rational is_only_to_be_used_by 'Mathematics
        }
        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * incoming allows_incoming_dependencies_from constraint validation
     */
    it should ("validate the allows_incoming_dependencies_from constraint with no violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Mathematics allows_incoming_dependencies_from 'Example
        }
        val result = specification.analyze().map(_.toString).toSeq.sorted.mkString("\n")
        result should be(empty)

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("validate the allows_incoming_dependencies_from constraint with violations") in {
        val specification = new Specification(project) {
            ensemble('Operations) { "mathematics.Operations*" }
            ensemble('Number) { "mathematics.Number*" }
            ensemble('Rational) { "mathematics.Rational*" }
            ensemble('Mathematics) { "mathematics.Mathematics*" }
            ensemble('Example) { "mathematics.Example*" }

            'Number allows_incoming_dependencies_from 'Rational
        }
        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * outgoing every_element_should_implement_method constraint
     */
    it should ("validate the every_element_should_implement_method constraint with no violations") in {
        val specification = new Specification(project) {
            ensemble('Mathematics)(ClassMatcher(
                "mathematics.Mathematics",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            'Mathematics every_element_should_implement_method MethodWithName("operation1")

        }
        specification.analyze() should be(empty)

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("validate the every_element_should_implement_method constraint with violations") in {
        val specification = new Specification(project) {
            ensemble('Operations)(ClassMatcher(
                "mathematics.Operations",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            'Operations every_element_should_implement_method MethodWithName("operation1")
        }
        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * outgoing every_element_should_extend constraint
     */
    it should ("validate the every_element_should_extend constraint with no violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {
            ensemble('Address)(ClassMatcher(
                "entity.impl.Address",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            ensemble('User)(ClassMatcher(
                "entity.impl.User",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            ensemble('AbstractEntity)(ClassMatcher(
                "entity.AbstractEntity",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            'Address every_element_should_extend 'AbstractEntity

        }

        specification.analyze() should be(empty)

        testEnsemblesAreNonEmpty(specification)
    }

    it should ("validate the every_element_should_extend constraint with violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {
            ensemble('Address)(ClassMatcher(
                "entity.impl.Address",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            ensemble('User)(ClassMatcher(
                "entity.impl.User",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            ensemble('AbstractEntity)(ClassMatcher(
                "entity.AbstractEntity",
                matchPrefix = false, matchMethods = false, matchFields = false
            ))

            'Address every_element_should_extend 'User

        }

        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)
    }

    /*
     * outgoing every_element_should_be_annotated_with constraint
     */
    it should ("validate every_element_should_be_annotated_with constraint with no violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {

            ensemble('EntityId)(FieldMatcher(AllClasses, theName = Some("id")))

            'EntityId every_element_should_be_annotated_with (AnnotatedWith("entity.annotation.Id"))
        }

        specification.analyze() should be(empty)

        testEnsemblesAreNonEmpty(specification)

    }

    it should ("validate every_element_should_be_annotated_with constraint with violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {

            ensemble('EntityId)(FieldMatcher(AllClasses, theName = Some("id")))

            'EntityId every_element_should_be_annotated_with (AnnotatedWith("entity.annotation.Entity"))
        }

        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)

    }

    /*
     * outgoing every_element_should_be_annotated_with (multiple annotations) constraint
     */
    it should ("validate every_element_should_be_annotated_with (multiple annotations) constraint with no violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {

            ensemble('EntityId)(FieldMatcher(AllClasses, theName = Some("id")))

            'EntityId every_element_should_be_annotated_with
                ("(entity.annotation.Id - entity.annotation.Column)",
                    Seq(
                        AnnotatedWith("entity.annotation.Id"),
                        AnnotatedWith(
                            "entity.annotation.Column",
                            "name" → StringValue("id"), "nullable" → BooleanValue(false)
                        )
                    ))
        }

        specification.analyze() should be(empty)

        testEnsemblesAreNonEmpty(specification)

    }

    it should ("validate every_element_should_be_annotated_with (multiple annotations) constraint with violations") in {
        val project = ClassFiles(locateTestResources("classfiles/entity.jar", "av"))
        val specification = new Specification(project) {

            ensemble('EntityId)(FieldMatcher(AllClasses, theName = Some("id")))

            'EntityId every_element_should_be_annotated_with
                ("(entity.annotation.Id - entity.annotation.Column)",
                    Seq(
                        AnnotatedWith("entity.annotation.Id"),
                        AnnotatedWith(
                            "entity.annotation.Column",
                            "name" → StringValue("id"), "nullable" → BooleanValue(true)
                        )
                    ))
        }

        specification.analyze() should not be (empty)

        testEnsemblesAreNonEmpty(specification)

    }

}
