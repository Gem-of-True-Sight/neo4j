/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.v3_5.logical.plans

import org.opencypher.v9_0.expressions._
import org.opencypher.v9_0.util.attribution.IdGen
import org.opencypher.v9_0.util.{InputPosition, LabelId, NonEmptyList, PropertyKeyId}

import scala.collection.mutable.ArrayBuffer

/**
  * Helper object for constructing node index operators from strings.
  */
object IndexSeek {

  // primitives
  private val ID = "([a-zA-Z][a-zA-Z0-9]*)"
  private val VALUE = "([0-9'].*)"
  private val INT = "([0-9])".r
  private val STRING = s"'(.*)'".r

  // entry point
  private val INDEX_SEEK_PATTERN = s"$ID: ?$ID ?\\(([^\\)]+)\\)".r

  // predicates
  private val EXACT = s"$ID ?= ?$VALUE".r
  private val EXISTS = s"$ID".r
  private val LESS_THAN = s"$ID ?< ?$VALUE".r
  private val LESS_THAN_OR_EQ = s"$ID ?<= ?$VALUE".r
  private val GREATER_THAN = s"$ID ?> ?$VALUE".r
  private val GREATER_THAN_OR_EQ = s"$ID ?>= ?$VALUE".r
  private val STARTS_WITH = s"$ID STARTS WITH $STRING".r
  private val ENDS_WITH = s"$ID ENDS WITH $STRING".r
  private val CONTAINS = s"$ID CONTAINS $STRING".r

  private val pos = InputPosition.NONE

  /**
    * Construct a node index seek/scan operator by parsing a string.
    */
  def apply(indexSeekString: String,
            getValue: GetValueFromIndexBehavior = DoNotGetValue,
            argumentIds: Set[String] = Set.empty)(implicit idGen: IdGen): IndexLeafPlan = {

    val INDEX_SEEK_PATTERN(node, labelStr, predicateStr) = indexSeekString.trim
    val label = LabelToken(labelStr, LabelId(0))
    val predicates = predicateStr.split(',').map(_.trim)

    def prop(prop: String) =
      IndexedProperty(PropertyKeyToken(PropertyKeyName(prop)(pos), PropertyKeyId(0)), getValue)

    def value(value: String): Literal =
      value match {
        case INT(int) => SignedDecimalIntegerLiteral(int)(pos)
        case STRING(str) => StringLiteral(str)(pos)
        case _ => throw new IllegalArgumentException(s"Value `$value` is not supported")
      }

    if (predicates.length == 1) {
      predicates.head match {
        case EXACT(propStr, valueStr) =>
          val valueExpr = SingleQueryExpression(value(valueStr))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case LESS_THAN(propStr, valueStr) =>
          val valueExpr = RangeQueryExpression(InequalitySeekRangeWrapper(RangeLessThan(NonEmptyList(ExclusiveBound(value(valueStr)))))(pos))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case LESS_THAN_OR_EQ(propStr, valueStr) =>
          val valueExpr = RangeQueryExpression(InequalitySeekRangeWrapper(RangeLessThan(NonEmptyList(InclusiveBound(value(valueStr)))))(pos))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case GREATER_THAN(propStr, valueStr) =>
          val valueExpr = RangeQueryExpression(InequalitySeekRangeWrapper(RangeGreaterThan(NonEmptyList(ExclusiveBound(value(valueStr)))))(pos))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case GREATER_THAN_OR_EQ(propStr, valueStr) =>
          val valueExpr = RangeQueryExpression(InequalitySeekRangeWrapper(RangeGreaterThan(NonEmptyList(InclusiveBound(value(valueStr)))))(pos))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case STARTS_WITH(propStr, string) =>
          val valueExpr = RangeQueryExpression(PrefixSeekRangeWrapper(PrefixRange(StringLiteral(string)(pos)))(pos))
          NodeIndexSeek(node, label, List(prop(propStr)), valueExpr, argumentIds)

        case ENDS_WITH(propStr, string) =>
          NodeIndexEndsWithScan(node, label, prop(propStr), StringLiteral(string)(pos), argumentIds)

        case CONTAINS(propStr, string) =>
          NodeIndexContainsScan(node, label, prop(propStr), StringLiteral(string)(pos), argumentIds)

        case EXISTS(propStr) =>
          NodeIndexScan(node, label, prop(propStr), argumentIds)
      }
    } else if (predicates.length > 1) {

      val properties = new ArrayBuffer[IndexedProperty]()
      val valueExprs = new ArrayBuffer[SingleQueryExpression[Expression]]()

      for (predicate <- predicates)
        predicate match {
          case EXACT(propStr, valueStr) =>
            valueExprs += SingleQueryExpression(value(valueStr))
            properties += prop(propStr)
          case _ => throw new IllegalArgumentException("Only exact predicates are allowed in composite seeks.")
        }

      NodeIndexSeek(node, label, properties, CompositeQueryExpression(valueExprs), argumentIds)
    } else
      throw new IllegalArgumentException("Cannot parse 'str' and index seek.")
  }
}
