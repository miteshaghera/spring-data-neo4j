/*
 * Copyright (c) 2019 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.core.cypher.renderer;

import static java.util.stream.Collectors.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.neo4j.core.cypher.*;
import org.springframework.data.neo4j.core.cypher.CompoundCondition.LogicalOperator;
import org.springframework.data.neo4j.core.cypher.support.ReflectiveVisitor;
import org.springframework.data.neo4j.core.cypher.support.TypedSubtree;
import org.springframework.data.neo4j.core.cypher.support.Visitable;
import org.springframework.lang.Nullable;

/**
 * This is a simple (some would call it naive) implementation of a visitor to the Cypher AST created by the Cypher builder
 * based on the {@link ReflectiveVisitor reflective visitor}.
 * <p/>
 * It takes care of separating elements of sub trees containing the element type with a separator and provides pairs of
 * {@code enter} / {@code leave} for the structuring elements of the Cypher AST as needed.
 * <p/>
 * This rendering visitor is not meant to be used outside framework code and we don't give any guarantees on the format
 * being output apart from that it works within the constraints of SDN-RX.
 *
 * @author Michael J. Simons
 * @author Gerrit Meier
 * @since 1.0
 */
class RenderingVisitor extends ReflectiveVisitor {

	private static final String LABEL_SEPARATOR = ":";
	private static final String TYPE_SEPARATOR = ":";
	private static final Pattern LABEL_AND_TYPE_QUOTATION = Pattern.compile("`");

	/**
	 * Target of all rendering.
	 */
	private final StringBuilder builder = new StringBuilder();

	/**
	 * Optional separator between elements.
	 */
	private String separator = null;

	/**
	 * This keeps track on which level of the tree a separator is needed.
	 */
	private final Set<Integer> separatorOnLevel = new HashSet<>();

	/**
	 * The current level in the tree of cypher elements.
	 */
	private int currentLevel = 0;

	private void enableSeparator(int level, boolean on) {
		if (on) {
			separatorOnLevel.add(level);
		} else {
			separatorOnLevel.remove(level);
		}
		this.separator = null;
	}

	private boolean needsSeparator() {
		return separatorOnLevel.contains(currentLevel);
	}

	@Override
	protected void preEnter(Visitable visitable) {

		int nextLevel = ++currentLevel + 1;
		if (visitable instanceof TypedSubtree) {
			enableSeparator(nextLevel, true);
		}

		if (needsSeparator() && separator != null) {
			builder.append(separator);
			separator = null;
		}
	}

	@Override
	protected void postLeave(Visitable visitable) {

		if (needsSeparator()) {
			separator = ", ";
		}

		if (visitable instanceof TypedSubtree) {
			enableSeparator(currentLevel + 1, false);
		}

		--currentLevel;
	}

	void enter(Match match) {
		if (match.isOptional()) {
			builder.append("OPTIONAL ");
		}
		builder.append("MATCH ");
	}

	void leave(Match match) {
		builder.append(" ");
	}

	void enter(Where where) {
		builder.append(" WHERE ");
	}

	void enter(Return returning) {
		builder.append("RETURN ");
	}

	void enter(Delete delete) {

		if (delete.isDetach()) {
			builder.append("DETACH ");
		}

		builder.append("DELETE ");
	}

	void leave(Delete match) {
		builder.append(" ");
	}

	void leave(AliasedExpression aliased) {
		builder.append(" AS ").append(aliased.getAlias());
	}

	void enter(Order order) {
		builder.append(" ORDER BY ");
	}

	void enter(Skip skip) {
		builder.append(" SKIP ");
	}

	void enter(Limit limit) {
		builder.append(" LIMIT ");
	}

	void enter(SortItem.Direction direction) {
		builder
			.append(" ")
			.append(direction.getSymbol());
	}

	void enter(Property property) {
		builder
			.append(property.getParentAlias())
			.append(".")
			.append(property.getName());
	}

	void enter(FunctionInvocation functionInvocation) {
		builder
			.append(functionInvocation.getFunctionName())
			.append("(");
	}

	void leave(FunctionInvocation functionInvocation) {
		builder
			.append(")");
	}

	void enter(Comparison comparison) {
		builder
			.append(" ")
			.append(comparison.getComparator())
			.append(" ");
	}

	void leave(IsNull isNull) {
		builder
			.append(" IS ")
			.append(isNull.isNegated() ? "NOT " : "")
			.append("NULL");
	}

	void enter(CompoundCondition compoundCondition) {
		builder.append("(");
	}

	void leave(CompoundCondition compoundCondition) {
		builder.append(")");
	}

	void enter(LogicalOperator logicalOperator) {
		builder
			.append(" ")
			.append(logicalOperator.getSymbol())
			.append(" ");
	}

	void enter(NotCondition notCondition) {
		builder
			.append("NOT (");
	}

	void leave(NotCondition notCondition) {
		builder
			.append(")");
	}

	void enter(Literal<?> expression) {
		builder.append(expression.asString());
	}

	void enter(Node node) {
		builder.append("(")
			.append(node.getSymbolicName().map(SymbolicName::getName).orElse(""))
			.append(node.isLabeled() ? LABEL_SEPARATOR : "")
			.append(node.getLabels().stream().map(RenderingVisitor::escapeName)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(joining(LABEL_SEPARATOR)))
			.append(")");
	}

	void enter(SymbolicName symbolicName) {
		builder.append(symbolicName.getName());
	}

	void enter(RelationshipDetail details) {

		Relationship.Direction direction = details.getDirection();
		builder.append(direction.getSymbolLeft());
		builder
			.append("[")
			.append(details.getSymbolicName().map(SymbolicName::getName).orElse(""))
			.append(details.isTyped() ? TYPE_SEPARATOR : "")
			.append(details.getTypes().stream().map(RenderingVisitor::escapeName)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(joining(TYPE_SEPARATOR)))
			.append("]");
		builder.append(direction.getSymbolRight());
	}

	public String getRenderedContent() {
		return this.builder.toString();
	}

	/**
	 * Escapes a symbolic name. Such a symbolic name is either used for a nodes label, the type of a relationship or a
	 * variable.
	 *
	 * @param unescapedName The name to escape.
	 * @return An empty optional when the unescaped name is {@literal null}, otherwise the correctly escaped name, safe to be used in statements.
	 */
	static Optional<String> escapeName(@Nullable CharSequence unescapedName) {

		if (unescapedName == null) {
			return Optional.empty();
		}

		Matcher matcher = LABEL_AND_TYPE_QUOTATION.matcher(unescapedName);
		return Optional.of(String.format(Locale.ENGLISH, "`%s`", matcher.replaceAll("``")));
	}

}

