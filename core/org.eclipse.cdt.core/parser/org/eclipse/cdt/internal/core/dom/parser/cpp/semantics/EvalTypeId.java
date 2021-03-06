/*******************************************************************************
 * Copyright (c) 2012, 2014 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Markus Schorn - initial API and implementation
 *     Sergey Prigogin (Google)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser.cpp.semantics;

import static org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.ExpressionTypes.typeFromReturnType;
import static org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.ExpressionTypes.valueCategoryFromReturnType;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.DOMException;
import org.eclipse.cdt.core.dom.ast.IASTExpression.ValueCategory;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IProblemBinding;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.IValue;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPClassTemplate;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPClassType;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPConstructor;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPFunction;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPMethod;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPSpecialization;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPTemplateParameterMap;
import org.eclipse.cdt.core.parser.util.ArrayUtil;
import org.eclipse.cdt.internal.core.dom.parser.ISerializableEvaluation;
import org.eclipse.cdt.internal.core.dom.parser.ITypeMarshalBuffer;
import org.eclipse.cdt.internal.core.dom.parser.Value;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPFunction;
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPPointerType;
import org.eclipse.cdt.internal.core.dom.parser.cpp.ClassTypeHelper;
import org.eclipse.cdt.internal.core.dom.parser.cpp.ClassTypeHelper.MethodKind;
import org.eclipse.cdt.internal.core.dom.parser.cpp.ICPPEvaluation;
import org.eclipse.cdt.internal.core.dom.parser.cpp.InstantiationContext;
import org.eclipse.core.runtime.CoreException;

/**
 * Performs evaluation of an expression.
 */
public class EvalTypeId extends CPPDependentEvaluation {
	private final IType fInputType;
	private final ICPPEvaluation[] fArguments;
	private final boolean fRepresentsNewExpression;
	private IType fOutputType;

	private ICPPFunction fConstructor = CPPFunction.UNINITIALIZED_FUNCTION;
	private boolean fCheckedIsTypeDependent;
	private boolean fIsTypeDependent;
	private boolean fCheckedIsConstantExpression;
	private boolean fIsConstantExpression;

	public EvalTypeId(IType type, IASTNode pointOfDefinition, ICPPEvaluation... arguments) {
		this(type, findEnclosingTemplate(pointOfDefinition), false, arguments);
	}

	public EvalTypeId(IType type, IBinding templateDefinition, ICPPEvaluation... arguments) {
		this(type, templateDefinition, false, arguments);
	}

	private EvalTypeId(IType type, IBinding templateDefinition, boolean forNewExpression, ICPPEvaluation... arguments) {
		super(templateDefinition);
		if (arguments == null)
			throw new NullPointerException("arguments"); //$NON-NLS-1$

		fInputType= type;
		fArguments= arguments;
		fRepresentsNewExpression = forNewExpression;
	}

	public static EvalTypeId createForNewExpression(IType type, IASTNode pointOfDefinition, ICPPEvaluation... arguments) {
		return new EvalTypeId(type, findEnclosingTemplate(pointOfDefinition), true, arguments);
	}

	public IType getInputType() {
		return fInputType;
	}

	public ICPPEvaluation[] getArguments() {
		return fArguments;
	}

	@Override
	public boolean isInitializerList() {
		return false;
	}

	@Override
	public boolean isFunctionSet() {
		return false;
	}

	@Override
	public IType getType(IASTNode point) {
		if (fOutputType == null) {
			fOutputType= computeType();
		}
		return fOutputType;
	}

	private IType computeType() {
		if (isTypeDependent())
			return new TypeOfDependentExpression(this);

		IType type = typeFromReturnType(fInputType);
		if (fRepresentsNewExpression)
			return new CPPPointerType(type);
		return type;
	}

	@Override
	public IValue getValue(IASTNode point) {
		if (isValueDependent())
			return Value.create(this);
		if (isTypeDependent())
			return Value.create(this);
		if (fRepresentsNewExpression)
			return Value.UNKNOWN;
		if (fOutputType instanceof ICPPClassType) {
			// TODO(sprigogin): Simulate execution of a ctor call.
			return Value.UNKNOWN;
		}
		if (fArguments.length == 1)
			return fArguments[0].getValue(point);
		return Value.UNKNOWN;
	}

	@Override
	public boolean isTypeDependent() {
		if (!fCheckedIsTypeDependent) {
			fCheckedIsTypeDependent = true;
			fIsTypeDependent = CPPTemplates.isDependentType(fInputType) || containsDependentType(fArguments);
		}
		return fIsTypeDependent;
	}

	@Override
	public boolean isValueDependent() {
		if (CPPTemplates.isDependentType(fInputType))
			return true;
		for (ICPPEvaluation arg : fArguments) {
			if (arg.isValueDependent())
				return true;
		}
		return false;
	}

	@Override
	public boolean isConstantExpression(IASTNode point) {
		if (!fCheckedIsConstantExpression) {
			fCheckedIsConstantExpression = true;
			fIsConstantExpression = computeIsConstantExpression(point);
		}
		return fIsConstantExpression;
	}

	private boolean computeIsConstantExpression(IASTNode point) {
		return !fRepresentsNewExpression
				&& areAllConstantExpressions(fArguments, point)
				&& isNullOrConstexprFunc(getConstructor(point));
	}

	@Override
	public ValueCategory getValueCategory(IASTNode point) {
		return valueCategoryFromReturnType(fInputType);
	}

	public ICPPFunction getConstructor(IASTNode point) {
		if (fConstructor == CPPFunction.UNINITIALIZED_FUNCTION) {
			fConstructor = computeConstructor(point);
		}
		return fConstructor;
	}

	private ICPPFunction computeConstructor(IASTNode point) {
		if (isTypeDependent())
			return null;

		IType simplifiedType = SemanticUtil.getNestedType(fInputType, SemanticUtil.TDEF);
		if (simplifiedType instanceof ICPPClassType) {
			ICPPClassType classType = (ICPPClassType) simplifiedType;
			ICPPEvaluation[] arguments = fArguments;
			ICPPConstructor[] constructors = ClassTypeHelper.getConstructors(classType, point);
			if (arguments.length == 1 && arguments[0] instanceof EvalInitList) {
				// List-initialization of a class (dcl.init.list-3).
				if (TypeTraits.isAggregateClass(classType, point)) {
					// Pretend that aggregate initialization is calling the default constructor.
					return findDefaultConstructor(classType, constructors);
				}
				if (((EvalInitList) arguments[0]).getClauses().length == 0) {
					ICPPMethod ctor = findDefaultConstructor(classType, constructors);
					if (ctor != null)
						return ctor;
				}
				ICPPConstructor[] ctors = getInitializerListConstructors(constructors, point);
				if (ctors.length != 0) {
					constructors = ctors;
				} else {
					arguments = ((EvalInitList) arguments[0]).getClauses();
				}
			}

			LookupData data = new LookupData(classType.getNameCharArray(), null, point);
			data.foundItems = constructors;
			data.setFunctionArguments(false, arguments);
			try {
				IBinding binding = CPPSemantics.resolveFunction(data, constructors, true);
				if (binding instanceof ICPPFunction) {
					return (ICPPFunction) binding;
				}
			} catch (DOMException e) {
				CCorePlugin.log(e);
			}
		}
		return null;
	}

	private ICPPConstructor findDefaultConstructor(ICPPClassType classType, ICPPConstructor[] constructors) {
		for (ICPPConstructor ctor : constructors) {
			if (ctor.isImplicit() && ClassTypeHelper.getMethodKind(classType, ctor) == MethodKind.DEFAULT_CTOR)
				return ctor;
		}
		return null;
	}

	/**
	 * Returns constructors that can be called by passing a single {@code std::initializer_list}
	 * as an argument.
	 */
	private ICPPConstructor[] getInitializerListConstructors(ICPPConstructor[] constructors, IASTNode point) {
		ICPPConstructor[] result = ICPPConstructor.EMPTY_CONSTRUCTOR_ARRAY;
		ICPPClassTemplate template = CPPVisitor.get_initializer_list(point);
		for (ICPPConstructor ctor : constructors) {
			if (ctor.getRequiredArgumentCount() <= 1) {
				IType[] parameterTypes = ctor.getType().getParameterTypes();
				if (parameterTypes.length != 0) {
					IType type = parameterTypes[0];
					if (type instanceof ICPPSpecialization) {
						IBinding specialized = ((ICPPSpecialization) type).getSpecializedBinding();
						if (specialized instanceof ICPPClassTemplate
								&& template.isSameType((IType) specialized)) {
							result = ArrayUtil.append(result, ctor);
						}
					}
				}
			}
		}
		return ArrayUtil.trim(result);
	}

	@Override
	public void marshal(ITypeMarshalBuffer buffer, boolean includeValue) throws CoreException {
		short firstBytes = ITypeMarshalBuffer.EVAL_TYPE_ID;
		if (fRepresentsNewExpression)
			firstBytes |= ITypeMarshalBuffer.FLAG1;

		buffer.putShort(firstBytes);
		buffer.marshalType(fInputType);
		buffer.putInt(fArguments.length);
		for (ICPPEvaluation arg : fArguments) {
			buffer.marshalEvaluation(arg, includeValue);
		}
		marshalTemplateDefinition(buffer);
	}

	public static ISerializableEvaluation unmarshal(short firstBytes, ITypeMarshalBuffer buffer)
			throws CoreException {
		IType type= buffer.unmarshalType();
		ICPPEvaluation[] args= null;
		int len= buffer.getInt();
		args = new ICPPEvaluation[len];
		for (int i = 0; i < args.length; i++) {
			args[i]= (ICPPEvaluation) buffer.unmarshalEvaluation();
		}
		IBinding templateDefinition= buffer.unmarshalBinding();
		boolean forNewExpression = (firstBytes & ITypeMarshalBuffer.FLAG1) != 0;
		return new EvalTypeId(type, templateDefinition, forNewExpression, args);
	}

	@Override
	public ICPPEvaluation instantiate(InstantiationContext context, int maxDepth) {
		ICPPEvaluation[] args= instantiateCommaSeparatedSubexpressions(fArguments, context, maxDepth);
		IType type = CPPTemplates.instantiateType(fInputType, context);
		if (args == fArguments && type == fInputType)
			return this;

		EvalTypeId result = new EvalTypeId(type, getTemplateDefinition(), fRepresentsNewExpression, args);

		if (!result.isTypeDependent()) {
			IType simplifiedType = SemanticUtil.getNestedType(type, SemanticUtil.TDEF);
			if (simplifiedType instanceof ICPPClassType) {
				// Check the constructor call and return EvalFixed.INCOMPLETE to indicate a substitution
				// failure if the call cannot be resolved.
				ICPPFunction constructor = result.getConstructor(context.getPoint());
				if (constructor == null || constructor instanceof IProblemBinding || constructor.isDeleted()) {
					return EvalFixed.INCOMPLETE;
				}
			}
		}

		return result;
	}

	@Override
	public ICPPEvaluation computeForFunctionCall(CPPFunctionParameterMap parameterMap,
			ConstexprEvaluationContext context) {
		ICPPEvaluation[] args = fArguments;
		for (int i = 0; i < fArguments.length; i++) {
			ICPPEvaluation arg = fArguments[i].computeForFunctionCall(parameterMap, context.recordStep());
			if (arg != fArguments[i]) {
				if (args == fArguments) {
					args = new ICPPEvaluation[fArguments.length];
					System.arraycopy(fArguments, 0, args, 0, fArguments.length);
				}
				args[i] = arg;
			}
		}
		if (args == fArguments)
			return this;
		return new EvalTypeId(fInputType, getTemplateDefinition(), fRepresentsNewExpression, args);
	}

	@Override
	public int determinePackSize(ICPPTemplateParameterMap tpMap) {
		int r = CPPTemplates.determinePackSize(fInputType, tpMap);
		for (ICPPEvaluation arg : fArguments) {
			r = CPPTemplates.combinePackSize(r, arg.determinePackSize(tpMap));
		}
		return r;
	}

	@Override
	public boolean referencesTemplateParameter() {
		for (ICPPEvaluation arg : fArguments) {
			if (arg.referencesTemplateParameter())
				return true;
		}
		return false;
	}
}
