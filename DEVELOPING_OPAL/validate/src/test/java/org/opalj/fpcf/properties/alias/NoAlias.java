/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.properties.alias;

import org.opalj.br.fpcf.FPCFAnalysis;
import org.opalj.fpcf.properties.PropertyValidator;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

/**
 * Annotation to specify that this element is part of a NoAlias relation.
 * <p>
 * The first element of the relation is the annotated element itself, the second element is specified by another
 * NoAlias annotation with the same id within the same class. The following exceptions apply:
 * <ul>
 *     <li>
 *         If aliasWithNull is true, the second element is the null value.
 *     </li>
 *     <li>
 *         If thisParameter is true, the second element is the this reference of the annotated method.
 *     </li>
 * </ul>
 */
@PropertyValidator(key = "AliasProperty", validator = NoAliasMatcher.class)
@Repeatable(NoAliases.class)
@Documented
@Target({TYPE_USE, PARAMETER, METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface NoAlias {

    /**
     * A short reasoning why this relation is a NoAlias relation.
     */
    String reason();

    /**
     * The id of this MayAlias relation.
     * It is used to associate this element with the other element that is part of this relation.
     * <p>
     * The id of the relation must be unique within the class.
     */
    int id();

    /**
     * true, iff the second element is the null value.
     */
    boolean aliasWithNull() default false;

    /**
     * true, iff the second element is the {@code this} parameter of the annotated method.
     */
    boolean thisParameter() default false;

    /**
     * The {@link Class} to which this relation belongs.
     */
    Class<?> clazz();

    /**
     * All analyses that should be able to correctly detect this relation.
     * @return All analyses that should be able to correctly detect this relation.
     */
    Class<? extends FPCFAnalysis>[] analyses() default {};
}
