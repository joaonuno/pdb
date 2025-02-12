/*
 * Copyright 2014 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.feedzai.commons.sql.abstraction.engine;

import com.feedzai.commons.sql.abstraction.ddl.*;
import com.feedzai.commons.sql.abstraction.dml.*;
import com.feedzai.commons.sql.abstraction.engine.configuration.PdbProperties;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.feedzai.commons.sql.abstraction.util.StringUtils.quotize;
import static com.feedzai.commons.sql.abstraction.util.StringUtils.singleQuotize;

/**
 * Abstract translator to be extended by specific implementations.
 * <p/>
 * This class already provides translations that are common to all databases.
 *
 * @author Rui Vilao (rui.vilao@feedzai.com)
 * @since 2.0.0
 */
public abstract class AbstractTranslator {
    /**
     * The properties in place.
     */
    @Inject
    protected PdbProperties properties;
    /**
     * The Guice injector.
     */
    @Inject
    protected Injector injector;

    /**
     * Injects dependencies on the given objects.
     *
     * @param objs The objects to be injected.
     */
    protected void inject(Expression... objs) {
        for (Object o : objs) {
            if (o == null) {
                continue;
            }

            injector.injectMembers(o);
        }
    }

    /**
     * Injects dependencies on the given objects.
     *
     * @param objs The objects to be injected.
     */
    protected void inject(Collection<? extends Expression> objs) {
        for (Object o : objs) {
            if (o == null) {
                continue;
            }

            injector.injectMembers(o);
        }
    }

    /**
     * Joins a collection of objects given the delimiter.
     *
     * @param list      The collection of objects to join.
     * @param delimiter The delimiter.
     * @return A String representing the given objects separated by the delimiter.
     */
    protected String join(Collection<?> list, String delimiter) {
        return Joiner.on(delimiter).join(list);
    }

    /**
     * Translates {@link Name}.
     *
     * @param n The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Name n) {
        final String name = n.getName();
        final String environment = n.getEnvironment();
        final List<String> res = new ArrayList<>();

        if (environment != null) {
            res.add(quotize(environment, translateEscape()) + "." + (n.isQuote() ? quotize(name, translateEscape()) : name));
        } else {
            res.add(n.isQuote() ? quotize(name, translateEscape()) : name);
        }

        if (n.getOrdering() != null) {
            res.add(n.getOrdering());
        }

        if (n.isIsNull()) {
            res.add("IS NULL");
        }

        if (n.isIsNotNull()) {
            res.add("IS NOT NULL");
        }

        if (n.isEnclosed()) {
            return "(" + StringUtils.join(res, " ") + ")";
        } else {
            return StringUtils.join(res, " ");
        }
    }

    /**
     * Translates {@link Between}.
     *
     * @param b The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Between b) {
        final Expression and = b.getAnd();
        final Expression column = b.getColumn();
        inject(and, column);

        String modifier = "BETWEEN";
        if (b.isNot()) {
            modifier = "NOT " + modifier;
        }

        String result = String.format("%s %s %s", column.translate(), modifier, and.translate());

        if (b.isEnclosed()) {
            result = "(" + result + ")";
        }

        return result;
    }

    /**
     * Translates {@link Coalesce}.
     *
     * @param c The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Coalesce c) {
        final Expression[] alternative = c.getAlternative();
        Expression exp = c.getExp();
        inject(exp);

        final String[] alts = new String[alternative.length];
        int i = 0;
        for (Expression e : alternative) {
            inject(e);
            alts[i] = e.translate();
            i++;
        }

        return String.format("COALESCE(%s, " + Joiner.on(", ").join(alts) + ")", exp.translate());
    }

    /**
     * Translates {@link Delete}.
     *
     * @param d The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Delete d) {
        final Expression table = d.getTable();
        final Expression where = d.getWhere();
        inject(table, where);

        final List<String> temp = new ArrayList<>();

        temp.add("DELETE FROM");
        temp.add(table.translate());

        if (where != null) {
            temp.add("WHERE");
            temp.add(where.translate());
        }

        return Joiner.on(" ").join(temp);
    }

    /**
     * Translates {@link Join}.
     *
     * @param j The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Join j) {
        final String join = j.getJoin();
        final Expression joinExpr = j.getJoinExpr();
        final Expression joinTable = j.getJoinTable();
        inject(joinExpr, joinTable);


        if (joinTable.isAliased()) {
            return String.format("%s %s %s ON (%s)", join, joinTable.translate(), quotize(joinTable.getAlias(), translateEscape()), joinExpr.translate());
        } else {
            return String.format("%s %s ON (%s)", join, joinTable.translate(), joinExpr.translate());
        }
    }


    /**
     * Translates {@link K}.
     *
     * @param k The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(K k) {
        final Object o = k.getConstant();

        String result;

        if (o != null) {
            if (!k.isQuote()) {
                result = o.toString();
            } else if (o instanceof String) {
                result = singleQuotize(StringEscapeUtils.escapeSql((String) o));
            } else if (o instanceof Boolean) {
                result = (Boolean) o ? translateTrue() : translateFalse();
            } else {
                result = o.toString();
            }
        } else {
            result = "NULL";
        }

        return k.isEnclosed() ? ("(" + result + ")") : result;
    }


    /**
     * Translates {@link Literal}.
     *
     * @param l The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Literal l) {
        return l.getLiteral().toString();
    }


    /**
     * Translates {@link Truncate}.
     *
     * @param t The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Truncate t) {
        final Expression table = t.getTable();
        inject(table);

        final List<String> temp = new ArrayList<>();

        temp.add("TRUNCATE TABLE");
        temp.add(table.translate());

        return join(temp, " ");
    }

    /**
     * Translates {@link Update}.
     *
     * @param u The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(Update u) {
        final List<Expression> columns = u.getColumns();
        final Expression table = u.getTable();
        final Expression where = u.getWhere();
        inject(table, where);


        final List<String> temp = new ArrayList<>();

        temp.add("UPDATE");
        temp.add(table.translate());
        if (table.isAliased()) {
            temp.add(quotize(table.getAlias(), translateEscape()));
        }
        temp.add("SET");
        List<String> setTranslations = new ArrayList<>();
        for (Expression e : columns) {
            inject(e);
            setTranslations.add(e.translate());
        }
        temp.add(join(setTranslations, ", "));

        if (where != null) {
            temp.add("WHERE");
            temp.add(where.translate());
        }

        return join(temp, " ");
    }

    /**
     * Translates the escape character.
     *
     * @return The string representation of the escape character.
     */
    public abstract String translateEscape();

    /**
     * Translates the boolean true.
     *
     * @return The string representation of the true keyword.
     */
    public abstract String translateTrue();

    /**
     * Translates the boolean false.
     *
     * @return The string representation of the false keyword.
     */
    public abstract String translateFalse();

    /**
     * Translates {@link AlterColumn}.
     *
     * @param ac The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(AlterColumn ac);

    /**
     * Translates {@link AddColumn}.
     *
     * @param ac The object to translate.
     * @return The string representation of the given object.
     */
    public String translate(AddColumn ac) {
        final DbColumn column = ac.getColumn();
        final Expression table = ac.getTable();
        final Name name = new Name(column.getName());

        inject(table, name);

        StringBuilder sb = new StringBuilder("ALTER TABLE ")
                .append(table.translate())
                .append(" ADD ")
                .append(name.translate())
                .append(" ")
                .append(translate(column))
                .append(" ");

        List<Object> trans = Lists.transform(column.getColumnConstraints(), new com.google.common.base.Function<DbColumnConstraint, Object>() {
            @Override
            public Object apply(DbColumnConstraint input) {
                return input.translate();
            }
        });

        sb.append(Joiner.on(" ").join(trans));

        return sb.toString();
    }

    /**
     * Translates {@link DropPrimaryKey}.
     *
     * @param dpk The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(DropPrimaryKey dpk);

    /**
     * Translates {@link Function}.
     *
     * @param f The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(Function f);

    /**
     * Translates {@link Modulo}.
     *
     * @param m The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(Modulo m);

    /**
     * Translates {@link Rename}.
     *
     * @param r The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(Rename r);

    /**
     * Translates {@link RepeatDelimiter}.
     *
     * @param rd The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(RepeatDelimiter rd);

    /**
     * Translates {@link Query}.
     *
     * @param q The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(Query q);

    /**
     * Translates {@link View}.
     *
     * @param v The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(View v);

    /**
     * Translates {@link DbColumn}.
     *
     * @param dc The object to translate.
     * @return The string representation of the given object.
     */
    public abstract String translate(DbColumn dc);
}
