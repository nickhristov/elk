/*******************************************************************************
 * Copyright (c) 2013, 2015 Kiel University and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Kiel University - initial API and implementation
 *******************************************************************************/
package org.eclipse.elk.alg.tree.intermediate;

import java.util.ArrayList;

import org.eclipse.elk.alg.tree.ILayoutProcessor;
import org.eclipse.elk.alg.tree.graph.TGraph;
import org.eclipse.elk.alg.tree.graph.TNode;
import org.eclipse.elk.alg.tree.properties.Properties;
import org.eclipse.elk.core.util.IElkProgressMonitor;

/**
 * A processor connect all roots of a given graph to a super root which then is the new root of the
 * graph.
 * 
 * @author sor
 * @author sgu
 */
public class RootProcessor implements ILayoutProcessor {

    private ArrayList<TNode> roots = new ArrayList<TNode>();

    /**
     * {@inheritDoc}
     */
    public void process(final TGraph tGraph, final IElkProgressMonitor progressMonitor) {

        /** clear list of roots if processor is reused */
        roots.clear();

        /** find all roots in the graph */
        for (TNode node : tGraph.getNodes()) {
            if (node.getIncomingEdges().isEmpty()) {
                node.setProperty(Properties.ROOT, true);
                roots.add(node);
            }
        }
        /**
         * if there are more than one root add a super root and set the current roots as its
         * children
         */
        switch (roots.size()) {
        case 0:
            assert tGraph.getNodes().isEmpty();
            TNode root = new TNode(0, tGraph, "DUMMY_ROOT");
            root.setProperty(Properties.ROOT, true);
            root.setProperty(Properties.DUMMY, true);
            tGraph.getNodes().add(root);
            break;
            
        case 1:
            // perfect, we already have only one root
            break;

        default:
            TNode superRoot = new TNode(0, tGraph, "SUPER_ROOT");

            for (TNode tRoot : roots) {
                superRoot.addChild(tRoot);
                tRoot.setProperty(Properties.ROOT, false);
            }
            superRoot.setProperty(Properties.ROOT, true);
            superRoot.setProperty(Properties.DUMMY, true);
            tGraph.getNodes().add(superRoot);
            break;
        }
    }
}