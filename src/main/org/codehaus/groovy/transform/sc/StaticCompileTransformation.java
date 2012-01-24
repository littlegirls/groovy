/*
 * Copyright 2003-2010 the original author or authors.
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
package org.codehaus.groovy.transform.sc;

import groovy.transform.CompileStatic;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.classgen.asm.WriterControllerFactory;
import org.codehaus.groovy.classgen.asm.sc.StaticTypesTypeChooser;
import org.codehaus.groovy.classgen.asm.sc.StaticTypesWriterControllerFactoryImpl;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.transform.StaticTypesTransformation;
import org.codehaus.groovy.transform.stc.*;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys.BINARY_EXP_TARGET;
import static org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys.STATIC_COMPILE_NODE;
import static org.codehaus.groovy.transform.stc.StaticTypesMarker.DIRECT_METHOD_CALL_TARGET;

/**
 * Handles the implementation of the {@link groovy.transform.CompileStatic} transformation.
 *
 * @author Cedric Champeau
 */
@GroovyASTTransformation(phase = CompilePhase.INSTRUCTION_SELECTION)
public class StaticCompileTransformation extends StaticTypesTransformation {

    public static final ClassNode COMPILE_STATIC_ANNOTATION = ClassHelper.make(CompileStatic.class);
    private static final ClassNode BYTECODE_ADAPTER_CLASS = ClassHelper.make(ScriptBytecodeAdapter.class);
    
    
    private final static Map<Integer, MethodNode> BYTECODE_BINARY_ADAPTERS = new HashMap<Integer, MethodNode>() {{
        put(Types.COMPARE_EQUAL, BYTECODE_ADAPTER_CLASS.getMethods("compareEqual").get(0));
        put(Types.COMPARE_GREATER_THAN, BYTECODE_ADAPTER_CLASS.getMethods("compareGreaterThan").get(0));
        put(Types.COMPARE_GREATER_THAN_EQUAL, BYTECODE_ADAPTER_CLASS.getMethods("compareGreaterThanEqual").get(0));
        put(Types.COMPARE_LESS_THAN, BYTECODE_ADAPTER_CLASS.getMethods("compareLessThan").get(0));
        put(Types.COMPARE_LESS_THAN_EQUAL, BYTECODE_ADAPTER_CLASS.getMethods("compareLessThanEqual").get(0));
        put(Types.COMPARE_NOT_EQUAL, BYTECODE_ADAPTER_CLASS.getMethods("compareNotEqual").get(0));
        put(Types.COMPARE_TO, BYTECODE_ADAPTER_CLASS.getMethods("compareTo").get(0));
        
    }};
    
    private final StaticTypesWriterControllerFactoryImpl factory = new StaticTypesWriterControllerFactoryImpl();

    @Override
    public void visit(final ASTNode[] nodes, final SourceUnit source) {
        BinaryExpressionTransformer transformer = new BinaryExpressionTransformer(source);

        AnnotatedNode node = (AnnotatedNode) nodes[1];
        StaticTypeCheckingVisitor visitor = null;
        if (node instanceof ClassNode) {
            ClassNode classNode = (ClassNode) node;
            classNode.putNodeMetaData(WriterControllerFactory.class, factory);
            node.putNodeMetaData(STATIC_COMPILE_NODE, Boolean.TRUE);
            visitor = newVisitor(source, classNode, null);
            visitor.visitClass(classNode);
        } else if (node instanceof MethodNode) {
            MethodNode methodNode = (MethodNode)node;
            methodNode.putNodeMetaData(STATIC_COMPILE_NODE, Boolean.TRUE);
            ClassNode declaringClass = methodNode.getDeclaringClass();
            if (declaringClass.getNodeMetaData(WriterControllerFactory.class)==null) {
                declaringClass.putNodeMetaData(WriterControllerFactory.class, factory);
            }
            visitor = newVisitor(source, declaringClass, null);
            visitor.setMethodsToBeVisited(Collections.singleton(methodNode));
            visitor.visitMethod(methodNode);
        } else {
            source.addError(new SyntaxException(STATIC_ERROR_PREFIX + "Unimplemented node type", node.getLineNumber(), node.getColumnNumber()));
        }
        if (visitor!=null) {
            visitor.performSecondPass();
        }
        if (node instanceof ClassNode) {
            transformer.visitClass((ClassNode)node);
        } else if (node instanceof MethodNode) {
            transformer.visitMethod((MethodNode)node);
        }
    }

    @Override
    protected StaticTypeCheckingVisitor newVisitor(final SourceUnit unit, final ClassNode node, final TypeCheckerPluginFactory pluginFactory) {
        return new StaticCompilationVisitor(unit, node, pluginFactory);
    }

    /**
     * Some expressions use symbols as aliases to method calls (<<, +=, ...). In static compilation,
     * if such a method call is found, we transform the original binary expression into a method
     * call expression so that the call gets statically compiled.
     *
     * @author Cedric Champeau
     */
    private static class BinaryExpressionTransformer extends ClassCodeExpressionTransformer {
        private final SourceUnit unit;

        private final StaticTypesTypeChooser typeChooser = new StaticTypesTypeChooser();
        private ClassNode classNode;

        private BinaryExpressionTransformer(final SourceUnit unit) {
            this.unit = unit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return unit;
        }

        @Override
        public Expression transform(Expression expr) {
            if (expr instanceof StaticMethodCallExpression) {
                return transformStaticMethodCallExpression((StaticMethodCallExpression) expr);
            }
            if (expr instanceof BinaryExpression) {
                return transformBinaryExpression(expr);
            }
            if (expr instanceof MethodCallExpression) {
                return transformMethodCallExpression((MethodCallExpression) expr);
            }
            if (expr instanceof ClosureExpression) {
                return transformClosureExpression((ClosureExpression)expr);
            }
            if (expr instanceof ConstructorCallExpression) {
                return transformConstructorCall((ConstructorCallExpression) expr);
            }
            return super.transform(expr);
        }

        private Expression transformClosureExpression(final ClosureExpression expr) {
            visitClassCodeContainer(expr.getCode());
            return expr;
        }

        @Override
        public void visitClass(final ClassNode node) {
            ClassNode prec = classNode;
            classNode = node;
            super.visitClass(node);
            Iterator<InnerClassNode> innerClasses = classNode.getInnerClasses();
            while (innerClasses.hasNext()) {
                InnerClassNode innerClassNode = innerClasses.next();
                visitClass(innerClassNode);
            }
            classNode = prec;
        }

        @Override
        public void visitClosureExpression(final ClosureExpression expression) {
            super.visitClosureExpression(expression);
        }

        private Expression transformConstructorCall(final ConstructorCallExpression expr) {
            ConstructorNode node = (ConstructorNode) expr.getNodeMetaData(DIRECT_METHOD_CALL_TARGET);
            if (node==null) return expr;
            if (node.getParameters().length==1 && StaticTypeCheckingSupport.implementsInterfaceOrIsSubclassOf(node.getParameters()[0].getType(), ClassHelper.MAP_TYPE)) {
                Expression arguments = expr.getArguments();
                if (arguments instanceof TupleExpression) {
                    TupleExpression tupleExpression = (TupleExpression) arguments;
                    List<Expression> expressions = tupleExpression.getExpressions();
                    if (expressions.size()==1) {
                        Expression expression = expressions.get(0);
                        if (expression instanceof MapExpression) {
                            MapExpression map = (MapExpression) expression;
                            // check that the node doesn't belong to the list of declared constructors
                            ClassNode declaringClass = node.getDeclaringClass();
                            for (ConstructorNode constructorNode : declaringClass.getDeclaredConstructors()) {
                                if (constructorNode==node) {
                                    return expr;
                                }
                            }
                            // replace this call with a call to <init>() + appropriate setters
                            // for example, foo(x:1, y:2) is replaced with:
                            // { def tmp = new Foo(); tmp.x = 1; tmp.y = 2; return tmp }()
                            
                            VariableExpression vexp = new VariableExpression("obj"+System.currentTimeMillis(), declaringClass);
                            ConstructorNode cn = new ConstructorNode(Opcodes.ACC_PUBLIC, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
                            cn.setDeclaringClass(declaringClass);
                            ConstructorCallExpression call = new ConstructorCallExpression(declaringClass, new ArgumentListExpression());
                            call.putNodeMetaData(DIRECT_METHOD_CALL_TARGET, cn);
                            DeclarationExpression declaration = new DeclarationExpression(
                                    vexp, Token.newSymbol("=", expr.getLineNumber(), expr.getColumnNumber()),
                                    call
                                    );
                            BlockStatement stmt = new BlockStatement();
                            stmt.addStatement(new ExpressionStatement(declaration));
                            for (MapEntryExpression entryExpression : map.getMapEntryExpressions()) {
                                int line = entryExpression.getLineNumber();
                                int col = entryExpression.getColumnNumber();
                                BinaryExpression bexp = new BinaryExpression(
                                        new PropertyExpression(vexp, entryExpression.getKeyExpression()),
                                        Token.newSymbol("=", line, col),
                                        entryExpression.getValueExpression()
                                );
                                stmt.addStatement(new ExpressionStatement(bexp));
                            }
                            stmt.addStatement(new ReturnStatement(vexp));
                            ClosureExpression cl = new ClosureExpression(Parameter.EMPTY_ARRAY, stmt);
                            MethodCallExpression result = new MethodCallExpression(cl, "call", ArgumentListExpression.EMPTY_ARGUMENTS);
                            result.setMethodTarget(StaticTypeCheckingVisitor.CLOSURE_CALL_NO_ARG);
                            VariableScopeVisitor visitor = new VariableScopeVisitor(unit);
                            visitor.visitClosureExpression(cl);
                            return result;
                        }
                    }
                }

            }
            return expr;
        }
        
        private Expression transformMethodCallExpression(final MethodCallExpression expr) {
            Expression objectExpression = expr.getObjectExpression();
            if (expr.isSafe()) {
                MethodCallExpression notSafe = new MethodCallExpression(
                        objectExpression,
                        expr.getMethod(),
                        expr.getArguments()
                );
                notSafe.copyNodeMetaData(expr);
                notSafe.setSpreadSafe(expr.isSpreadSafe());
                notSafe.setSourcePosition(expr);
                notSafe.setMethodTarget(expr.getMethodTarget());
                TernaryExpression texpr = new TernaryExpression(
                        new BooleanExpression(new BinaryExpression(
                                objectExpression,
                                Token.newSymbol("!=", objectExpression.getLineNumber(), objectExpression.getColumnNumber()),
                                ConstantExpression.NULL
                        )),
                        notSafe,
                        ConstantExpression.NULL);
                return transform(texpr);

            }
            ClassNode type = typeChooser.resolveType(objectExpression, classNode);
            if (type!=null && type.isArray()) {
                String method = expr.getMethodAsString();
                ClassNode componentType = type.getComponentType();
                if ("getAt".equals(method)) {
                    Expression arguments = expr.getArguments();
                    if (arguments instanceof TupleExpression) {
                        List<Expression> argList = ((TupleExpression)arguments).getExpressions();
                        if (argList.size()==1) {
                            Expression indexExpr = argList.get(0);
                            ClassNode argType = typeChooser.resolveType(indexExpr, classNode);
                            ClassNode indexType = ClassHelper.getWrapper(argType);
                            if (componentType.isEnum() && ClassHelper.Number_TYPE==indexType) {
                                // workaround for generated code in enums which use .next() returning a Number
                                indexType = ClassHelper.Integer_TYPE;
                            }
                            if (argType!=null && ClassHelper.Integer_TYPE==indexType) {
                                BinaryExpression binaryExpression = new BinaryExpression(
                                        objectExpression,
                                        Token.newSymbol("[", indexExpr.getLineNumber(), indexExpr.getColumnNumber()),
                                        indexExpr
                                );
                                binaryExpression.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, componentType);
                                return binaryExpression;
                            }
                        }
                    }
                }
                if ("putAt".equals(method)) {
                    Expression arguments = expr.getArguments();
                    if (arguments instanceof TupleExpression) {
                        List<Expression> argList = ((TupleExpression)arguments).getExpressions();
                        if (argList.size()==2) {
                            Expression indexExpr = argList.get(0);
                            Expression objExpr = argList.get(1);
                            ClassNode argType = typeChooser.resolveType(indexExpr, classNode);
                            if (argType!=null && ClassHelper.Integer_TYPE==ClassHelper.getWrapper(argType)) {
                                BinaryExpression arrayGet = new BinaryExpression(
                                        objectExpression,
                                        Token.newSymbol("[", indexExpr.getLineNumber(), indexExpr.getColumnNumber()),
                                        indexExpr
                                );
                                arrayGet.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, componentType);
                                BinaryExpression assignment = new BinaryExpression(
                                        arrayGet,
                                        Token.newSymbol("=", objExpr.getLineNumber(), objExpr.getColumnNumber()),
                                        objExpr
                                );
                                return assignment;
                            }
                        }
                    }
                }
            }
            return super.transform(expr);
        }

        private Expression transformBinaryExpression(final Expression expr) {
            Object[] list = (Object[]) expr.getNodeMetaData(BINARY_EXP_TARGET);
            if (list!=null) {
                BinaryExpression bin = (BinaryExpression) expr;
                Token operation = bin.getOperation();
                boolean isAssignment = StaticTypeCheckingSupport.isAssignment(operation.getType());
                MethodCallExpression call;
                MethodNode node = (MethodNode) list[0];
                String name = (String) list[1];
                Expression left = transform(bin.getLeftExpression());
                Expression right = transform(bin.getRightExpression());
                call = new MethodCallExpression(
                        left,
                        name,
                        new ArgumentListExpression(right)
                );
                call.setMethodTarget(node);
                ClassExpression sba = new ClassExpression(BYTECODE_ADAPTER_CLASS);
                MethodNode adapter = BYTECODE_BINARY_ADAPTERS.get(operation.getType());
                if (adapter!=null) {
                    // replace with compareEquals
                    call = new MethodCallExpression(sba,
                            "compareEquals",
                            new ArgumentListExpression(left, right));
                    call.setMethodTarget(adapter);
                }
                if (!isAssignment) return call;
                // case of +=, -=, /=, ...
                // the method represents the operation type only, and we must add an assignment
                return new BinaryExpression(left, Token.newSymbol("=", operation.getStartLine(), operation.getStartColumn()), call);
            }
            return super.transform(expr);
        }

        private Expression transformStaticMethodCallExpression(final StaticMethodCallExpression orig) {
            MethodNode target = (MethodNode) orig.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
            if (target!=null) {
                MethodCallExpression call = new MethodCallExpression(
                        new ClassExpression(orig.getOwnerType()),
                        orig.getMethod(),
                        orig.getArguments()
                );
                call.setMethodTarget(target);
                return call;
            }
            return super.transform(orig);
        }
    }

}
