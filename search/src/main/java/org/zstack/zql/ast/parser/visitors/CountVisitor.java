package org.zstack.zql.ast.parser.visitors;

import org.zstack.header.zql.ASTNode;
import org.zstack.zql.antlr4.ZQLBaseVisitor;
import org.zstack.zql.antlr4.ZQLParser;

import java.util.stream.Collectors;

public class CountVisitor extends ZQLBaseVisitor<ASTNode.Query> {
    @Override
    public ASTNode.Query visitCount(ZQLParser.CountContext ctx) {
        ASTNode.Query q = new ASTNode.Query();
        q.setTarget(ctx.queryTarget().accept(new QueryTargetVisitor()));
        q.setConditions(ctx.condition() == null ? null : ctx.condition().stream().map(it->it.accept(new ConditionVisitor())).collect(Collectors.toList()));
        q.setRestrictBy(ctx.restrictBy() == null ? null : ctx.restrictBy().accept(new RestrictByVisitor()));
        q.setOrderBy(ctx.orderBy() == null ? null : ctx.orderBy().accept(new OrderByVisitor()));
        q.setLimit(ctx.limit() == null ? null : ctx.limit().accept(new LimitVisitor()));
        q.setOffset(ctx.offset() == null ? null : ctx.offset().accept(new OffsetVisitor()));
        q.setName(ctx.namedAs() == null ? null : ctx.namedAs().accept(new NamedAsVisitor()));
        return q;
    }
}
