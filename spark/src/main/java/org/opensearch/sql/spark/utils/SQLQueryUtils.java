/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spark.utils;

import java.util.Locale;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.opensearch.sql.common.antlr.CaseInsensitiveCharStream;
import org.opensearch.sql.common.antlr.SyntaxAnalysisErrorListener;
import org.opensearch.sql.common.antlr.SyntaxCheckException;
import org.opensearch.sql.spark.antlr.parser.FlintSparkSqlExtensionsBaseVisitor;
import org.opensearch.sql.spark.antlr.parser.FlintSparkSqlExtensionsLexer;
import org.opensearch.sql.spark.antlr.parser.FlintSparkSqlExtensionsParser;
import org.opensearch.sql.spark.antlr.parser.SqlBaseLexer;
import org.opensearch.sql.spark.antlr.parser.SqlBaseParser;
import org.opensearch.sql.spark.antlr.parser.SqlBaseParserBaseVisitor;
import org.opensearch.sql.spark.dispatcher.model.FullyQualifiedTableName;
import org.opensearch.sql.spark.dispatcher.model.IndexQueryActionType;
import org.opensearch.sql.spark.dispatcher.model.IndexQueryDetails;
import org.opensearch.sql.spark.flint.FlintIndexType;

/**
 * This util class parses spark sql query and provides util functions to identify indexName,
 * tableName and datasourceName.
 */
@UtilityClass
public class SQLQueryUtils {

  // TODO Handle cases where the query has multiple table Names.
  public static FullyQualifiedTableName extractFullyQualifiedTableName(String sqlQuery) {
    SqlBaseParser sqlBaseParser =
        new SqlBaseParser(
            new CommonTokenStream(new SqlBaseLexer(new CaseInsensitiveCharStream(sqlQuery))));
    sqlBaseParser.addErrorListener(new SyntaxAnalysisErrorListener());
    SqlBaseParser.StatementContext statement = sqlBaseParser.statement();
    SparkSqlTableNameVisitor sparkSqlTableNameVisitor = new SparkSqlTableNameVisitor();
    statement.accept(sparkSqlTableNameVisitor);
    return sparkSqlTableNameVisitor.getFullyQualifiedTableName();
  }

  public static IndexQueryDetails extractIndexDetails(String sqlQuery) {
    FlintSparkSqlExtensionsParser flintSparkSqlExtensionsParser =
        new FlintSparkSqlExtensionsParser(
            new CommonTokenStream(
                new FlintSparkSqlExtensionsLexer(new CaseInsensitiveCharStream(sqlQuery))));
    flintSparkSqlExtensionsParser.addErrorListener(new SyntaxAnalysisErrorListener());
    FlintSparkSqlExtensionsParser.StatementContext statementContext =
        flintSparkSqlExtensionsParser.statement();
    FlintSQLIndexDetailsVisitor flintSQLIndexDetailsVisitor = new FlintSQLIndexDetailsVisitor();
    statementContext.accept(flintSQLIndexDetailsVisitor);
    return flintSQLIndexDetailsVisitor.getIndexQueryDetailsBuilder().build();
  }

  public static boolean isFlintExtensionQuery(String sqlQuery) {
    FlintSparkSqlExtensionsParser flintSparkSqlExtensionsParser =
        new FlintSparkSqlExtensionsParser(
            new CommonTokenStream(
                new FlintSparkSqlExtensionsLexer(new CaseInsensitiveCharStream(sqlQuery))));
    flintSparkSqlExtensionsParser.addErrorListener(new SyntaxAnalysisErrorListener());
    try {
      flintSparkSqlExtensionsParser.statement();
      return true;
    } catch (SyntaxCheckException syntaxCheckException) {
      return false;
    }
  }

  public static class SparkSqlTableNameVisitor extends SqlBaseParserBaseVisitor<Void> {

    @Getter private FullyQualifiedTableName fullyQualifiedTableName;

    public SparkSqlTableNameVisitor() {
      this.fullyQualifiedTableName = new FullyQualifiedTableName();
    }

    @Override
    public Void visitTableName(SqlBaseParser.TableNameContext ctx) {
      fullyQualifiedTableName = new FullyQualifiedTableName(ctx.getText());
      return super.visitTableName(ctx);
    }

    @Override
    public Void visitDropTable(SqlBaseParser.DropTableContext ctx) {
      for (ParseTree parseTree : ctx.children) {
        if (parseTree instanceof SqlBaseParser.IdentifierReferenceContext) {
          fullyQualifiedTableName = new FullyQualifiedTableName(parseTree.getText());
        }
      }
      return super.visitDropTable(ctx);
    }

    @Override
    public Void visitDescribeRelation(SqlBaseParser.DescribeRelationContext ctx) {
      for (ParseTree parseTree : ctx.children) {
        if (parseTree instanceof SqlBaseParser.IdentifierReferenceContext) {
          fullyQualifiedTableName = new FullyQualifiedTableName(parseTree.getText());
        }
      }
      return super.visitDescribeRelation(ctx);
    }

    // Extract table name for create Table Statement.
    @Override
    public Void visitCreateTableHeader(SqlBaseParser.CreateTableHeaderContext ctx) {
      for (ParseTree parseTree : ctx.children) {
        if (parseTree instanceof SqlBaseParser.IdentifierReferenceContext) {
          fullyQualifiedTableName = new FullyQualifiedTableName(parseTree.getText());
        }
      }
      return super.visitCreateTableHeader(ctx);
    }
  }

  public static class FlintSQLIndexDetailsVisitor extends FlintSparkSqlExtensionsBaseVisitor<Void> {

    @Getter private final IndexQueryDetails.IndexQueryDetailsBuilder indexQueryDetailsBuilder;

    public FlintSQLIndexDetailsVisitor() {
      this.indexQueryDetailsBuilder = new IndexQueryDetails.IndexQueryDetailsBuilder();
    }

    @Override
    public Void visitIndexName(FlintSparkSqlExtensionsParser.IndexNameContext ctx) {
      indexQueryDetailsBuilder.indexName(ctx.getText());
      return super.visitIndexName(ctx);
    }

    @Override
    public Void visitTableName(FlintSparkSqlExtensionsParser.TableNameContext ctx) {
      indexQueryDetailsBuilder.fullyQualifiedTableName(new FullyQualifiedTableName(ctx.getText()));
      return super.visitTableName(ctx);
    }

    @Override
    public Void visitCreateSkippingIndexStatement(
        FlintSparkSqlExtensionsParser.CreateSkippingIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.CREATE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.SKIPPING);
      visitPropertyList(ctx.propertyList());
      return super.visitCreateSkippingIndexStatement(ctx);
    }

    @Override
    public Void visitCreateCoveringIndexStatement(
        FlintSparkSqlExtensionsParser.CreateCoveringIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.CREATE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.COVERING);
      visitPropertyList(ctx.propertyList());
      return super.visitCreateCoveringIndexStatement(ctx);
    }

    @Override
    public Void visitCreateMaterializedViewStatement(
        FlintSparkSqlExtensionsParser.CreateMaterializedViewStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.CREATE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.MATERIALIZED_VIEW);
      indexQueryDetailsBuilder.mvName(ctx.mvName.getText());
      visitPropertyList(ctx.propertyList());
      return super.visitCreateMaterializedViewStatement(ctx);
    }

    @Override
    public Void visitDropCoveringIndexStatement(
        FlintSparkSqlExtensionsParser.DropCoveringIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DROP);
      indexQueryDetailsBuilder.indexType(FlintIndexType.COVERING);
      return super.visitDropCoveringIndexStatement(ctx);
    }

    @Override
    public Void visitDropSkippingIndexStatement(
        FlintSparkSqlExtensionsParser.DropSkippingIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DROP);
      indexQueryDetailsBuilder.indexType(FlintIndexType.SKIPPING);
      return super.visitDropSkippingIndexStatement(ctx);
    }

    @Override
    public Void visitDropMaterializedViewStatement(
        FlintSparkSqlExtensionsParser.DropMaterializedViewStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DROP);
      indexQueryDetailsBuilder.indexType(FlintIndexType.MATERIALIZED_VIEW);
      indexQueryDetailsBuilder.mvName(ctx.mvName.getText());
      return super.visitDropMaterializedViewStatement(ctx);
    }

    @Override
    public Void visitDescribeCoveringIndexStatement(
        FlintSparkSqlExtensionsParser.DescribeCoveringIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DESCRIBE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.COVERING);
      return super.visitDescribeCoveringIndexStatement(ctx);
    }

    @Override
    public Void visitDescribeSkippingIndexStatement(
        FlintSparkSqlExtensionsParser.DescribeSkippingIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DESCRIBE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.SKIPPING);
      return super.visitDescribeSkippingIndexStatement(ctx);
    }

    @Override
    public Void visitDescribeMaterializedViewStatement(
        FlintSparkSqlExtensionsParser.DescribeMaterializedViewStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.DESCRIBE);
      indexQueryDetailsBuilder.indexType(FlintIndexType.MATERIALIZED_VIEW);
      indexQueryDetailsBuilder.mvName(ctx.mvName.getText());
      return super.visitDescribeMaterializedViewStatement(ctx);
    }

    @Override
    public Void visitShowCoveringIndexStatement(
        FlintSparkSqlExtensionsParser.ShowCoveringIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.SHOW);
      indexQueryDetailsBuilder.indexType(FlintIndexType.COVERING);
      return super.visitShowCoveringIndexStatement(ctx);
    }

    @Override
    public Void visitShowMaterializedViewStatement(
        FlintSparkSqlExtensionsParser.ShowMaterializedViewStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.SHOW);
      indexQueryDetailsBuilder.indexType(FlintIndexType.MATERIALIZED_VIEW);
      return super.visitShowMaterializedViewStatement(ctx);
    }

    @Override
    public Void visitRefreshCoveringIndexStatement(
        FlintSparkSqlExtensionsParser.RefreshCoveringIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.REFRESH);
      indexQueryDetailsBuilder.indexType(FlintIndexType.COVERING);
      return super.visitRefreshCoveringIndexStatement(ctx);
    }

    @Override
    public Void visitRefreshSkippingIndexStatement(
        FlintSparkSqlExtensionsParser.RefreshSkippingIndexStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.REFRESH);
      indexQueryDetailsBuilder.indexType(FlintIndexType.SKIPPING);
      return super.visitRefreshSkippingIndexStatement(ctx);
    }

    @Override
    public Void visitRefreshMaterializedViewStatement(
        FlintSparkSqlExtensionsParser.RefreshMaterializedViewStatementContext ctx) {
      indexQueryDetailsBuilder.indexQueryActionType(IndexQueryActionType.REFRESH);
      indexQueryDetailsBuilder.indexType(FlintIndexType.MATERIALIZED_VIEW);
      indexQueryDetailsBuilder.mvName(ctx.mvName.getText());
      return super.visitRefreshMaterializedViewStatement(ctx);
    }

    @Override
    public Void visitPropertyList(FlintSparkSqlExtensionsParser.PropertyListContext ctx) {
      if (ctx != null) {
        ctx.property()
            .forEach(
                property -> {
                  // todo. Currently, we use contains() api to avoid unescape string. In future, we
                  //  should leverage
                  // https://github.com/apache/spark/blob/v3.5.0/sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkParserUtils.scala#L35 to unescape string literal
                  if (propertyKey(property.key).toLowerCase(Locale.ROOT).contains("auto_refresh")) {
                    if (propertyValue(property.value).toLowerCase(Locale.ROOT).contains("true")) {
                      indexQueryDetailsBuilder.autoRefresh(true);
                    }
                  }
                });
      }
      return null;
    }

    private String propertyKey(FlintSparkSqlExtensionsParser.PropertyKeyContext key) {
      if (key.STRING() != null) {
        return key.STRING().getText();
      } else {
        return key.getText();
      }
    }

    private String propertyValue(FlintSparkSqlExtensionsParser.PropertyValueContext value) {
      if (value.STRING() != null) {
        return value.STRING().getText();
      } else if (value.booleanValue() != null) {
        return value.getText();
      } else {
        return value.getText();
      }
    }
  }
}
