/*******************************************************************************
 * Copyright (c) 2012, 2013 Andrew Gvozdev and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Andrew Gvozdev - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.managedbuilder.internal.envvar;

import org.eclipse.cdt.core.cdtvariables.ICdtVariable;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.internal.core.cdtvariables.CdtVariableManager;
import org.eclipse.cdt.internal.core.cdtvariables.ICoreVariableContextInfo;
import org.eclipse.cdt.internal.core.envvar.EnvironmentVariableManager;
import org.eclipse.cdt.internal.core.envvar.ICoreEnvironmentVariableSupplier;
import org.eclipse.cdt.internal.core.envvar.IEnvironmentContextInfo;
import org.eclipse.cdt.managedbuilder.core.IToolChain;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.managedbuilder.envvar.IConfigurationEnvironmentVariableSupplier;
import org.eclipse.cdt.managedbuilder.envvar.IEnvironmentVariableProvider;
import org.eclipse.cdt.utils.cdtvariables.ICdtVariableSupplier;
import org.eclipse.cdt.utils.cdtvariables.IVariableContextInfo;

/**
 * Helper class to resolve environment variables directly from toolchain. The intention is
 * to use that in New Project Wizard and other scenarios when no configuration is available yet.
 * 
 * @noextend This class is not intended to be subclassed by clients.
 */
public class EnvironmentVariableManagerToolChain extends EnvironmentVariableManager {
	private final IToolChain toolChain;

	public EnvironmentVariableManagerToolChain(IToolChain toolchain) {
		this.toolChain = toolchain;
	}

	/**
	 * Wrapper class to deliver appropriate set of environment variable suppliers.
	 */
	private class ToolChainEnvironmentContextInfo implements IEnvironmentContextInfo {
		private final ICoreEnvironmentVariableSupplier fToolchainSupplier;
		private ToolChainEnvironmentContextInfo(IToolChain toolChain) {
			fToolchainSupplier = new ToolChainEnvironmentVariableSupplier(toolChain);
		}
		@Override
		public IEnvironmentContextInfo getNext() {
			return null;
		}
		@Override
		public ICoreEnvironmentVariableSupplier[] getSuppliers() {
			return new ICoreEnvironmentVariableSupplier[] {
					fUserSupplier,
					fToolchainSupplier,
					fEclipseSupplier,
				};
		}
		@Override
		public Object getContext() {
			return null;
		}
	}
	/**
	 * Tool-chain variable supplier
	 */
	private class ToolChainEnvironmentVariableSupplier implements ICoreEnvironmentVariableSupplier {
		private final IEnvironmentVariableProvider environmentVariableProvider = EnvironmentVariableProvider.getDefault();
		private final IConfigurationEnvironmentVariableSupplier toolchainSupplier;

		private ToolChainEnvironmentVariableSupplier(IToolChain toolChain) {
			this.toolchainSupplier = toolChain.getEnvironmentVariableSupplier();
		}
		@Override
		public IEnvironmentVariable getVariable(String name, Object context) {
			if (toolchainSupplier == null) {
				return null;
			}
			return toolchainSupplier.getVariable(name, null, environmentVariableProvider);
		}
		@Override
		public IEnvironmentVariable[] getVariables(Object context) {
			if (toolchainSupplier == null) {
				return new IEnvironmentVariable[0];
			}
			return toolchainSupplier.getVariables(null, environmentVariableProvider);
		}
		@Override
		public boolean appendEnvironment(Object context) {
			return true;
		}
	}

	/**
	 * Wrapper class to deliver appropriate set of suppliers for variable substitution.
	 */
	private final class ToolChainCoreVariableContextInfo implements ICoreVariableContextInfo {
		private final ToolChainCdtVariableSupplier fToolChainSupplier;
		private ToolChainCoreVariableContextInfo(IToolChain toolChain) {
			fToolChainSupplier = new ToolChainCdtVariableSupplier(toolChain);
		}
		@Override
		public ICdtVariableSupplier[] getSuppliers() {
			return new ICdtVariableSupplier[] {
					CdtVariableManager.fUserDefinedMacroSupplier,
					fToolChainSupplier,
					CdtVariableManager.fEnvironmentMacroSupplier,
					CdtVariableManager.fCdtMacroSupplier,
					CdtVariableManager.fEclipseVariablesMacroSupplier,
				};
		}
		@Override
		public IVariableContextInfo getNext() {
			return null;
		}
		@Override
		public int getContextType() {
			return CONTEXT_WORKSPACE;
		}
		@Override
		public Object getContextData() {
			return null;
		}
	}
	/**
	 * Tool-chain supplier for variable substitution.
	 */
	private class ToolChainCdtVariableSupplier implements ICdtVariableSupplier {
		private final IConfigurationEnvironmentVariableSupplier toolchainSupplier;
		private ToolChainCdtVariableSupplier(IToolChain toolChain) {
			this.toolchainSupplier = toolChain.getEnvironmentVariableSupplier();
		}
		@Override
		public ICdtVariable getVariable(String macroName, IVariableContextInfo context) {
			IEnvironmentVariable var = toolchainSupplier.getVariable(macroName, null, ManagedBuildManager.getEnvironmentVariableProvider());
			return CdtVariableManager.fEnvironmentMacroSupplier.createBuildMacro(var);
		}
		@Override
		public ICdtVariable[] getVariables(IVariableContextInfo context) {
			IEnvironmentVariable[] vars = toolchainSupplier.getVariables(null, ManagedBuildManager.getEnvironmentVariableProvider());
			if (vars != null) {
				ICdtVariable[] cdtVars = new ICdtVariable[vars.length];
				for (int i = 0; i < vars.length; i++) {
					cdtVars[i] = CdtVariableManager.fEnvironmentMacroSupplier.createBuildMacro(vars[i]);
				}
			}
			return null;
		}
	}

	/**
	 * Returns context info object which defines environment variable suppliers including tool-chain supplier.
	 */
	@Override
	public IEnvironmentContextInfo getContextInfo(Object level) {
		return new ToolChainEnvironmentContextInfo(toolChain);
	}

	/**
	 * Returns context info object which defines environment variable substitutions including that of tool-chain.
	 */
	@Override
	public ICoreVariableContextInfo getMacroContextInfoForContext(Object context) {
		return new ToolChainCoreVariableContextInfo(toolChain);
	}

}
