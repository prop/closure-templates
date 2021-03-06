/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.jssrc.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.template.soy.base.BaseUtils;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.exprparse.ExpressionParser;
import com.google.template.soy.exprparse.ParseException;
import com.google.template.soy.exprparse.TokenMgrError;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.jssrc.restricted.JsExprUtils;
import com.google.template.soy.jssrc.restricted.SoyJsSrcPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CssNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.jssrc.GoogMsgRefNode;

import java.util.Deque;
import java.util.List;
import java.util.Map;


/**
 * Visitor for generating JS expressions for parse tree nodes.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p> Precondition: MsgNode should not exist in the tree.
 *
 * @author Kai Huang
 */
public class GenJsExprsVisitor extends AbstractSoyNodeVisitor<List<JsExpr>> {


  /**
   * Injectable factory for creating an instance of this class.
   */
  public static interface GenJsExprsVisitorFactory {

    /**
     * @param localVarTranslations The current stack of replacement JS expressions for the local
     *     variables (and foreach-loop special functions) current in scope.
     */
    public GenJsExprsVisitor create(Deque<Map<String, JsExpr>> localVarTranslations);
  }


  /** Map of all SoyJsSrcPrintDirectives (name to directive). */
  Map<String, SoyJsSrcPrintDirective> soyJsSrcDirectivesMap;

  /** Instance of JsExprTranslator to use. */
  private final JsExprTranslator jsExprTranslator;

  /** Instance of GenCallCodeUtils to use. */
  private final GenCallCodeUtils genCallCodeUtils;

  /** The IsComputableAsJsExprsVisitor used by this instance (when needed). */
  private final IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor;

  /** Factory for creating an instance of GenJsExprsVisitor. */
  private final GenJsExprsVisitorFactory genJsExprsVisitorFactory;

  /** The current stack of replacement JS expressions for the local variables (and foreach-loop
   *  special functions) current in scope. */
  private final Deque<Map<String, JsExpr>> localVarTranslations;

  /** List to collect the results. */
  private List<JsExpr> jsExprs;


  /**
   * @param soyJsSrcDirectivesMap Map of all SoyJsSrcPrintDirectives (name to directive).
   * @param jsExprTranslator Instance of JsExprTranslator to use.
   * @param genCallCodeUtils Instance of GenCallCodeUtils to use.
   * @param isComputableAsJsExprsVisitor The IsComputableAsJsExprsVisitor used by this instance
   *     (when needed).
   * @param genJsExprsVisitorFactory Factory for creating an instance of GenJsExprsVisitor.
   * @param localVarTranslations The current stack of replacement JS expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   */
  @AssistedInject
  GenJsExprsVisitor(
      Map<String, SoyJsSrcPrintDirective> soyJsSrcDirectivesMap, JsExprTranslator jsExprTranslator,
      GenCallCodeUtils genCallCodeUtils, IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor,
      GenJsExprsVisitorFactory genJsExprsVisitorFactory,
      @Assisted Deque<Map<String, JsExpr>> localVarTranslations) {
    this.soyJsSrcDirectivesMap = soyJsSrcDirectivesMap;
    this.jsExprTranslator = jsExprTranslator;
    this.genCallCodeUtils = genCallCodeUtils;
    this.isComputableAsJsExprsVisitor = isComputableAsJsExprsVisitor;
    this.genJsExprsVisitorFactory = genJsExprsVisitorFactory;
    this.localVarTranslations = localVarTranslations;
  }


  @Override public List<JsExpr> exec(SoyNode node) {
    Preconditions.checkArgument(isComputableAsJsExprsVisitor.exec(node));
    return super.exec(node);
  }


  @Override protected void setup() {
    jsExprs = Lists.newArrayList();
  }


  @Override protected List<JsExpr> getResult() {
    return jsExprs;
  }


  // -----------------------------------------------------------------------------------------------
  // Implementations for concrete classes.


  @Override protected void visitInternal(TemplateNode node) {
    visitChildren(node);
  }


  /**
   * Example:
   * <pre>
   *   I'm feeling lucky!
   * </pre>
   * generates
   * <pre>
   *   'I\'m feeling lucky!'
   * </pre>
   */
  @Override protected void visitInternal(RawTextNode node) {

    // Note: BaseUtils.escapeToSoyString() builds a Soy string, which is usually a valid JS string.
    // The rare exception is a string containing a Unicode Format character (Unicode category "Cf")
    // because of the JavaScript language quirk that requires all category "Cf" characters to be
    // escaped in JS strings. Therefore, we must call JsSrcUtils.escapeUnicodeFormatChars() on the
    // result.
    String exprText = BaseUtils.escapeToSoyString(node.getRawText(), false);
    exprText = JsSrcUtils.escapeUnicodeFormatChars(exprText);
    jsExprs.add(new JsExpr(exprText, Integer.MAX_VALUE));
  }


  /**
   * Example:
   * <pre>
   *   MSG_UNNAMED_42
   * </pre>
   */
  @Override protected void visitInternal(GoogMsgRefNode node) {
    jsExprs.add(new JsExpr(node.getGoogMsgName(), Integer.MAX_VALUE));
  }


  /**
   * Example:
   * <pre>{@literal
   *   <a href="{$url}">
   * }</pre>
   * might generate
   * <pre>{@literal
   *   '<a href="' + opt_data.url + '">'
   * }</pre>
   */
  @Override protected void visitInternal(MsgHtmlTagNode node) {
    visitChildren(node);
  }


  /**
   * Example:
   * <pre>
   *   {$boo.foo}
   *   {$goo.moo + 5}
   * </pre>
   * might generate
   * <pre>
   *   opt_data.boo.foo
   *   gooData4.moo + 5
   * </pre>
   */
  @Override protected void visitInternal(PrintNode node) {

    JsExpr jsExpr = jsExprTranslator.translateToJsExpr(
        node.getExpr(), node.getExprText(), localVarTranslations);

    // Process directives.
    for (PrintDirectiveNode directiveNode : node.getChildren()) {

      // Get directive.
      SoyJsSrcPrintDirective directive = soyJsSrcDirectivesMap.get(directiveNode.getName());
      if (directive == null) {
        throw new SoySyntaxException(
            "Failed to find SoyJsSrcPrintDirective with name '" + directiveNode.getName() + "'" +
            " (tag " + node.toSourceString() +")");
      }

      // Get directive args.
      List<ExprRootNode<ExprNode>> args = directiveNode.getArgs();
      if (! directive.getValidArgsSizes().contains(args.size())) {
        throw new SoySyntaxException(
            "Print directive '" + directiveNode.getName() + "' used with the wrong number of" +
            " arguments (tag " + node.toSourceString() + ").");
      }

      // Translate directive args.
      List<JsExpr> argsJsExprs = Lists.newArrayListWithCapacity(args.size());
      for (ExprRootNode<ExprNode> arg : args) {
        argsJsExprs.add(jsExprTranslator.translateToJsExpr(arg, null, localVarTranslations));
      }

      // Apply directive.
      jsExpr = directive.applyForJsSrc(jsExpr, argsJsExprs);
    }

    jsExprs.add(jsExpr);
  }


  /**
   * Note: We would only see a CssNode if the css-handling scheme is BACKEND_SPECIFIC.
   *
   * Example:
   * <pre>
   *   {css selected-option}
   *   {css $foo, bar}
   * </pre>
   * might generate
   * <pre>
   *   goog.getCssName('selected-option')
   *   goog.getCssName(opt_data.foo, 'bar')
   * </pre>
   */
  @Override protected void visitInternal(CssNode node) {

    StringBuilder sb = new StringBuilder();
    sb.append("goog.getCssName(");

    String selectorText = node.getCommandText();

    int delimPos = node.getCommandText().lastIndexOf(',');
    if (delimPos != -1) {
      String baseText = node.getCommandText().substring(0, delimPos).trim();

      ExprRootNode<ExprNode> baseExpr = null;
      try {
        baseExpr = (new ExpressionParser(baseText)).parseExpression();
      } catch (TokenMgrError tme) {
        throw createExceptionForInvalidBase(baseText, tme);
      } catch (ParseException pe) {
        throw createExceptionForInvalidBase(baseText, pe);
      }
      
      JsExpr baseJsExpr =
          jsExprTranslator.translateToJsExpr(baseExpr, baseText, localVarTranslations);
      sb.append(baseJsExpr.getText()).append(", ");
      selectorText = node.getCommandText().substring(delimPos + 1).trim();
    }

    sb.append("'").append(selectorText).append("')");

    jsExprs.add(new JsExpr(sb.toString(), Integer.MAX_VALUE));
  }

  
  /**
   * Private helper for {@link #visitInternal(CssNode)}.
   * @param baseText The base part of the goog.getCssName() call being generated.
   * @param cause The underlying exception.
   * @return The SoySyntaxException to be thrown.
   */
  private SoySyntaxException createExceptionForInvalidBase(String baseText, Throwable cause) {
    //noinspection ThrowableInstanceNeverThrown
    return new SoySyntaxException(
        "Invalid expression for base in 'css' command text \"" + baseText + "\".", cause);
  }


  /**
   * Example:
   * <pre>
   *   {if $boo}
   *     AAA
   *   {elseif $foo}
   *     BBB
   *   {else}
   *     CCC
   *   {/if}
   * </pre>
   * might generate
   * <pre>
   *   (opt_data.boo) ? AAA : (opt_data.foo) ? BBB : CCC
   * </pre>
   */
  @Override protected void visitInternal(IfNode node) {

    // Create another instance of this visitor class for generating JS expressions from children.
    GenJsExprsVisitor genJsExprsVisitor = genJsExprsVisitorFactory.create(localVarTranslations);

    StringBuilder jsExprTextSb = new StringBuilder();

    boolean hasElse = false;
    for (SoyNode child : node.getChildren()) {

      if (child instanceof IfCondNode) {
        IfCondNode icn = (IfCondNode) child;

        JsExpr condJsExpr = jsExprTranslator.translateToJsExpr(
            icn.getExpr(), icn.getExprText(), localVarTranslations);
        jsExprTextSb.append("(").append(condJsExpr.getText()).append(") ? ");

        List<JsExpr> condBlockJsExprs = genJsExprsVisitor.exec(icn);
        jsExprTextSb.append(JsExprUtils.concatJsExprs(condBlockJsExprs).getText());

        jsExprTextSb.append(" : ");

      } else if (child instanceof IfElseNode) {
        hasElse = true;
        IfElseNode ien = (IfElseNode) child;

        List<JsExpr> elseBlockJsExprs = genJsExprsVisitor.exec(ien);
        jsExprTextSb.append(JsExprUtils.concatJsExprs(elseBlockJsExprs).getText());

      } else {
        throw new AssertionError();
      }
    }

    if (!hasElse) {
      jsExprTextSb.append("''");
    }

    jsExprs.add(new JsExpr(jsExprTextSb.toString(), Operator.CONDITIONAL.getPrecedence()));
  }


  @Override protected void visitInternal(IfCondNode node) {
    visitChildren(node);
  }


  @Override protected void visitInternal(IfElseNode node) {
    visitChildren(node);
  }


  /**
   * Example:
   * <pre>
   *   {call name="some.func" data="all" /}
   *   {call name="some.func" data="$boo.foo" /}
   *   {call name="some.func"}
   *     {param key="goo" value="$moo" /}
   *   {/call}
   *   {call name="some.func" data="$boo"}
   *     {param key="goo"}Blah{/param}
   *   {/call}
   * </pre>
   * might generate
   * <pre>
   *   some.func(opt_data)
   *   some.func(opt_data.boo.foo)
   *   some.func({goo: opt_data.moo})
   *   some.func(soy.$$augmentData(opt_data.boo, {goo: 'Blah'}))
   * </pre>
   */
  @Override protected void visitInternal(CallNode node) {
    jsExprs.add(genCallCodeUtils.genCallExpr(node, localVarTranslations));
  }


  @Override protected void visitInternal(CallParamContentNode node) {
    visitChildren(node);
  }

}
