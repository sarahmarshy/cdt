/*******************************************************************************
 * Copyright (c) 2004, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andrew Niefer (IBM Corporation) - initial API and implementation
 *     Markus Schorn (Wind River Systems)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser.cpp;

import org.eclipse.cdt.core.dom.ast.IASTDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.ICompositeType;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTQualifiedName;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPClassScope;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPClassType;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPField;
import org.eclipse.cdt.internal.core.dom.parser.ProblemBinding;

/**
 * Binding for a field.
 */
public class CPPField extends CPPVariable implements ICPPField {
    public static class CPPFieldProblem extends ProblemBinding implements ICPPField {
        private ICPPClassType fOwner;

		public CPPFieldProblem(ICPPClassType owner, IASTNode node, int id, char[] arg) {
            super(node, id, arg);
            fOwner= owner;
        }

        @Override
		public int getVisibility() {
            return v_private;
        }

        @Override
		public ICPPClassType getClassOwner() {
            return fOwner;
        }

		@Override
		public ICompositeType getCompositeTypeOwner() {
			return getClassOwner();
		}
    }

	public CPPField(IASTName name) {
		super(name);
	}

	@Override
	public int getVisibility() {
		return VariableHelpers.getVisibility(this);
	}

	@Override
	public ICPPClassType getClassOwner() {
		ICPPClassScope scope = (ICPPClassScope) getScope();
		return scope.getClassType();
	}

    @Override
	public boolean isStatic() {
        // Definition of a static field doesn't necessarily say static.
		if (getDeclarations() == null) {
			IASTNode def= getDefinition();
			if (def instanceof ICPPASTQualifiedName) {
				return true;
			}
		}
		return super.isStatic();
	}

    @Override
	public boolean isMutable() {
        return hasStorageClass(IASTDeclSpecifier.sc_mutable);
    }

    @Override
	public boolean isExtern() {
        // 7.1.1-5 The extern specifier can not be used in the declaration of class members.
        return false;
    }

	@Override
	public ICompositeType getCompositeTypeOwner() {
		return getClassOwner();
	}
}
