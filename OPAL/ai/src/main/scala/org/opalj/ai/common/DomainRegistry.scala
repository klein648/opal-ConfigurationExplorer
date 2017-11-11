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
package common

import org.opalj.br.Method
import org.opalj.br.analyses.SomeProject

/**
 * Registry for all domains that can be instantiated given a `Project`, and a `Method` with a
 * body.
 *
 * The registry was developed to support tools for enabling the automatic selection of a domain
 * that satisfies a given set of requirements; it also support debugging purposes that let
 * the user/developer choose between different domains. After choosing a domain,
 * an abstract interpretation can be performed.
 *
 * The compatible domains that are part of OPAL are already registered.
 *
 * ==Thread Safety==
 * The registry is thread safe.
 *
 * @author Michael Eichberg
 */
object DomainRegistry {

    case class DomainMetaInformation(
            lessPreciseDomains: Set[Class[_ <: Domain]],
            factory:            (SomeProject, Method) ⇒ Domain
    )

    type ClassRegistry = Map[Class[_ <: Domain], DomainMetaInformation]

    private[this] var descriptions: Map[String, Class[_ <: Domain]] = Map.empty
    private[this] var classRegistry: ClassRegistry = Map.empty

    /**
     * Register a new domain that can be used to perform an abstract interpretation
     * of a specific method.
     *
     * @param  domainDescription A short description of the properties of the domain;
     *         in particular w.r.t. the kind of computations the domain does.
     * @param  lessPreciseDomains The set of domains which are less precise/costly than this domain.
     *         This basically defines a partial order between the domains.
     * @param  domainClass The class of the domain.
     * @param  factory The factory method that will be used to create instances of the
     *      domain.
     */
    def register(
        domainDescription:  String,
        domainClass:        Class[_ <: Domain],
        lessPreciseDomains: Set[Class[_ <: Domain]],
        factory:            (SomeProject, Method) ⇒ Domain
    ): Unit = {
        this.synchronized {
            if (classRegistry.get(domainClass).nonEmpty)
                throw new IllegalArgumentException(s"$domainClass is already registered");

            descriptions += ((domainDescription, domainClass))
            classRegistry += ((domainClass, DomainMetaInformation(lessPreciseDomains, factory)))
        }
    }

    /** The transitive hull of all less precise domains of the given domain. */
    def allLessPreciseDomains(rootDomainClass: Class[_ <: Domain]): Set[Class[_ <: Domain]] = {
        var domains = Set.empty[Class[_ <: Domain]]
        var domainsToAnalyze = classRegistry(rootDomainClass).lessPreciseDomains
        while (domainsToAnalyze.nonEmpty) {
            val domain = domainsToAnalyze.head
            domainsToAnalyze = domainsToAnalyze.tail
            domains += domain
            classRegistry(domain).lessPreciseDomains.foreach { d ⇒
                if (!domains.contains(d)) domainsToAnalyze += d
            }
        }

        domains
    }

    def selectCandidates(requirements: Seq[Class[_ <: AnyRef]]): Set[Class[_ <: Domain]] = {
        classRegistry.keys.filter { candidate ⇒
            requirements.forall(r ⇒ r.isAssignableFrom(candidate))
        }.toSet
    }

    /**
     * Selects a domain that satisfies all requirements and which – according to the domains' partial
     * order is the most precise one. If the most precise one is not unique multiple domains are
     * returned; if no domain satisfies the requirements an empty sequence is returned.
     *
     * @example
     *          To get a domain use:
     *          {{{
     *          selectBest(Seq(classOf[RecordDefUse],classOf[IntegerRangeValues] ))
     *          }}}
     *
     * @return The best domain satisfying the stated requirements.
     */
    def selectBest(requirements: Seq[Class[_ <: AnyRef]]): Set[Class[_ <: Domain]] = {
        val candidateClasses = selectCandidates(requirements)
        if (candidateClasses.isEmpty)
            return Set.empty;

        val d: Class[_ <: Domain] = candidateClasses.head
        val rootSet = Set[Class[_ <: Domain]](d)
        val (best, _) = candidateClasses.tail.foldLeft((rootSet, allLessPreciseDomains(d))) { (c, n) ⇒
            // select the most precise domains...
            val (candidateDomains, lessPreciseThanCurrent) = c
            if (lessPreciseThanCurrent.contains(n))
                c
            else {
                val lessPreciseThanN = allLessPreciseDomains(n)
                (
                    (candidateDomains -- lessPreciseThanN) + n,
                    lessPreciseThanCurrent ++ lessPreciseThanN
                )
            }
        }
        best
    }

    def selectCheapest(requirements: Seq[Class[_ <: AnyRef]]): Set[Class[_ <: Domain]] = {
        val candidateClasses = selectCandidates(requirements)
        if (candidateClasses.isEmpty)
            return Set.empty;

        val d: Class[_ <: Domain] = candidateClasses.head
        val rootSet = Set[Class[_ <: Domain]](d)
        candidateClasses.tail.foldLeft(rootSet) { (c, n) ⇒
            // select the least precise/cheapest domains...
            val lessPreciseThanN = allLessPreciseDomains(n)
            if (lessPreciseThanN.exists(c.contains)) {
                // we already have a less precise domain...
                c
            } else {
                // This one is less precise than all other, is one the other acutally
                // more precise than N
                c.filter(c ⇒ !allLessPreciseDomains(c).contains(n)) + n
            }
        }
    }

    /**
     * Returns an `Iterable` to make it possible to iterate over the descriptions of
     * the domain. Useful to show the (end-users) some meaningful descriptions.
     */
    def domainDescriptions(): Iterable[String] = this.synchronized {
        for ((d, c) ← descriptions) yield s"[${c.getName}] $d"
    }

    /**
     * Returns the current view of the registry.
     */
    def registry: ClassRegistry = this.synchronized { classRegistry }

    /**
     * Creates a new instance of the domain identified by the given `domainDescription`.
     *
     * @param domainDescription The description that identifies the domain.
     * @param project The project.
     * @param method A method with a body.
     */
    // primarily introduced to facilitate the interaction with Java
    def newDomain(
        domainDescription: String,
        project:           SomeProject,
        method:            Method
    ): Domain = {
        this.synchronized {
            val domainClass: Class[_ <: Domain] = descriptions(domainDescription)
            newDomain(domainClass, project, method)
        }
    }

    /**
     * Creates a new instance of the domain identified by the given `domainClass`. To
     * create the instance the registered factory method will be used.
     *
     * @param domainClass The class object of the domain.
     * @param project The project.
     * @param method A method with a body.
     */
    def newDomain(domainClass: Class[_ <: Domain], project: SomeProject, method: Method): Domain = {
        this.synchronized { classRegistry(domainClass).factory(project, method) }
    }

    // initialize the registry with the known default domains

    // IMPROVE Add functionality to the domains to provide a description and then use that information when registering the domain factory
    register(
        "computations are done at the type level",
        classOf[domain.l0.BaseDomain[_]],
        Set.empty,
        (project: SomeProject, method: Method) ⇒ new domain.l0.BaseDomain(project, method)
    )

    register(
        "computations are done at the type level; cfg and def/use information is recorded",
        classOf[domain.l0.BaseDomainWithDefUse[_]],
        Set(classOf[domain.l0.BaseDomain[_]]),
        (project: SomeProject, method: Method) ⇒ new domain.l0.BaseDomainWithDefUse(project, method)
    )

    register(
        "computations related to int values are done using intervals",
        classOf[domain.l1.DefaultIntervalValuesDomain[_]],
        Set(classOf[domain.l0.BaseDomain[_]]),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l1.DefaultIntervalValuesDomain(project, method)
        }
    )

    register(
        "computations related to int/long values are done using sets",
        classOf[domain.l1.DefaultSetValuesDomain[_]],
        Set(classOf[domain.l0.BaseDomain[_]]),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l1.DefaultSetValuesDomain(project, method)
        }
    )

    register(
        "computations related to reference types track nullness, must alias and origin information",
        classOf[domain.l1.DefaultReferenceValuesDomain[_]],
        Set(classOf[domain.l0.BaseDomain[_]]),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l1.DefaultReferenceValuesDomain(project, method)
        }
    )

    register(
        "computations related to ints use intervals; tracks nullness, must alias and origin information of reference values",
        classOf[domain.l1.DefaultDomain[_]],
        Set(classOf[domain.l0.BaseDomain[_]]),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l1.DefaultDomain(project, method)
        }
    )

    register(
        "uses intervals for int values and track nullness and must alias information for reference types; records the ai-time def-use information",
        classOf[domain.l1.DefaultDomainWithCFGAndDefUse[_]],
        Set(classOf[domain.l0.BaseDomainWithDefUse[_]]),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l1.DefaultDomainWithCFGAndDefUse(project, method)
        }
    )

    register(
        "performs simple method invocations additionally to performing int computations using intervals and ",
        classOf[domain.l2.DefaultPerformInvocationsDomain[_]],
        Set(
            classOf[domain.l1.DefaultIntervalValuesDomain[_]],
            classOf[domain.l1.DefaultReferenceValuesDomain[_]]
        ),
        (project: SomeProject, method: Method) ⇒ {
            new domain.l2.DefaultPerformInvocationsDomain(project, method)
        }
    )

    register(
        "called methods are context-sensitively analyzed (up to two levels per default)",
        classOf[domain.l2.DefaultDomain[_]],
        Set(classOf[domain.l2.DefaultPerformInvocationsDomain[_]]),
        (project: SomeProject, method: Method) ⇒ new domain.l2.DefaultDomain(project, method)
    )

    register(
        "reuses information provided by some pre analyses",
        classOf[domain.la.DefaultDomain[_]],
        Set(classOf[domain.l1.DefaultDomain[_]]),
        (project: SomeProject, method: Method) ⇒ new domain.l1.DefaultDomain(project, method)
    )

}
