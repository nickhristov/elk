/*******************************************************************************
 * Copyright (c) 2016 Kiel University and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Kiel University - initial API and implementation
 *******************************************************************************/
package org.eclipse.elk.alg.graphviz.dot.parser.antlr;

import com.google.inject.Inject;
import org.eclipse.elk.alg.graphviz.dot.parser.antlr.internal.InternalGraphvizDotParser;
import org.eclipse.elk.alg.graphviz.dot.services.GraphvizDotGrammarAccess;
import org.eclipse.xtext.parser.antlr.AbstractAntlrParser;
import org.eclipse.xtext.parser.antlr.XtextTokenStream;

public class GraphvizDotParser extends AbstractAntlrParser {

	@Inject
	private GraphvizDotGrammarAccess grammarAccess;

	@Override
	protected void setInitialHiddenTokens(XtextTokenStream tokenStream) {
		tokenStream.setInitialHiddenTokens("RULE_WS", "RULE_ML_COMMENT", "RULE_SL_COMMENT", "RULE_PREC_LINE");
	}
	

	@Override
	protected InternalGraphvizDotParser createParser(XtextTokenStream stream) {
		return new InternalGraphvizDotParser(stream, getGrammarAccess());
	}

	@Override 
	protected String getDefaultRuleName() {
		return "GraphvizModel";
	}

	public GraphvizDotGrammarAccess getGrammarAccess() {
		return this.grammarAccess;
	}

	public void setGrammarAccess(GraphvizDotGrammarAccess grammarAccess) {
		this.grammarAccess = grammarAccess;
	}
}
