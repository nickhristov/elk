/*******************************************************************************
 * Copyright (c) 2015 Kiel University and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Kiel University - initial API and implementation
 *******************************************************************************/
package org.eclipse.elk.alg.layered.graph.transform;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.eclipse.elk.alg.layered.graph.LEdge;
import org.eclipse.elk.alg.layered.graph.LGraph;
import org.eclipse.elk.alg.layered.graph.LGraphElement;
import org.eclipse.elk.alg.layered.graph.LGraphUtil;
import org.eclipse.elk.alg.layered.graph.LInsets;
import org.eclipse.elk.alg.layered.graph.LLabel;
import org.eclipse.elk.alg.layered.graph.LNode;
import org.eclipse.elk.alg.layered.graph.LPort;
import org.eclipse.elk.alg.layered.p3order.CrossingMinimizationStrategy;
import org.eclipse.elk.alg.layered.p4nodes.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.properties.GraphProperties;
import org.eclipse.elk.alg.layered.properties.InternalProperties;
import org.eclipse.elk.alg.layered.properties.LayeredOptions;
import org.eclipse.elk.alg.layered.properties.PortType;
import org.eclipse.elk.core.UnsupportedGraphException;
import org.eclipse.elk.core.labels.LabelManagementOptions;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.math.KVector;
import org.eclipse.elk.core.math.KVectorChain;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeLabelPlacement;
import org.eclipse.elk.core.options.HierarchyHandling;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortLabelPlacement;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.ElkUtil;
import org.eclipse.elk.core.util.adapters.ElkGraphAdapters;
import org.eclipse.elk.core.util.labelspacing.LabelSpaceCalculation;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkGraphElement;
import org.eclipse.elk.graph.ElkLabel;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Implements the graph import aspect of {@link ElkGraphTransformer}.
 * 
 * @author cds
 */
class ElkGraphImporter {
    
    /** map between ElkGraph nodes / ports and the LGraph nodes / ports created for them. */
    private final Map<ElkGraphElement, LGraphElement> nodeAndPortMap = Maps.newHashMap();
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Import Entry Points

    /**
     * Imports the given graph.
     * 
     * @param elkgraph
     *            the graph to import.
     * @return the transformed graph.
     */
    public LGraph importGraph(final ElkNode elkgraph) {
        // Create the layered graph
        final LGraph topLevelGraph = createLGraph(elkgraph);
        
        // Transform the external ports, if any
        Set<GraphProperties> graphProperties = topLevelGraph.getProperty(InternalProperties.GRAPH_PROPERTIES);
        checkExternalPorts(elkgraph, graphProperties);
        if (graphProperties.contains(GraphProperties.EXTERNAL_PORTS)) {
            for (ElkPort elkport : elkgraph.getPorts()) {
                transformExternalPort(elkgraph, topLevelGraph, elkport);
            }
        }
        
        if (topLevelGraph.getProperty(LayeredOptions.PARTITIONING_ACTIVATE)) {
            graphProperties.add(GraphProperties.PARTITIONS);
        }

        // Import the graph either with or without multiple nested levels of hierarchy
        if (elkgraph.getProperty(LayeredOptions.HIERARCHY_HANDLING) == HierarchyHandling.INCLUDE_CHILDREN) {
            importHierarchicalGraph(elkgraph, topLevelGraph);
        } else {
            importFlatGraph(elkgraph, topLevelGraph);
        }
        
        return topLevelGraph;
    }

    /**
     * Imports the direct children of the given graph.
     * 
     * @param elkgraph
     *            graph to import.
     * @param lgraph
     *            graph to add the imported elements to.
     */
    private void importFlatGraph(final ElkNode elkgraph, final LGraph lgraph) {
        // Transform the node's children, unless we're told not to
        for (ElkNode child : elkgraph.getChildren()) {
            if (!child.getProperty(LayeredOptions.NO_LAYOUT)) {
                transformNode(child, lgraph);
            }
        }
        
        // Transform the outgoing edges of children (this is not part of the previous loop since all
        // children must have already been transformed)
        for (ElkNode child : elkgraph.getChildren()) {
            // Is inside self loop processing enabled for this node?
            boolean enableInsideSelfLoops = child.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE);
            
            for (ElkEdge elkedge : ElkGraphUtil.allOutgoingEdges(child)) {
                // Find the edge's target node
                ElkNode targetNode = ElkGraphUtil.connectableShapeToNode(elkedge.getTargets().get(0));
                
                // Find out basic information about the edge
                boolean isToBeLaidOut = !elkedge.getProperty(LayeredOptions.NO_LAYOUT);
                boolean isInsideSelfLoop = enableInsideSelfLoops && elkedge.isSelfloop()
                        && elkedge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
                boolean connectsToGraph = targetNode == elkgraph;
                boolean connectsToSibling = targetNode.getParent() == elkgraph;
                
                // Only transform the edge if we are to layout the edge and if it stays in the current
                // level of hierarchy (which implies that we don't transform inside self loops)
                if (isToBeLaidOut && !isInsideSelfLoop && (connectsToGraph || connectsToSibling)) {
                    transformEdge(elkedge, elkgraph, lgraph);
                }
            }
        }
        
        // Transform the outgoing edges of the graph itself (either inside self loops or edges connected
        // to its children)
        boolean enableInsideSelfLoops = elkgraph.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE);
        
        for (ElkEdge elkedge : elkgraph.getOutgoingEdges()) {
            // Find the edge's target node
            ElkNode targetNode = ElkGraphUtil.connectableShapeToNode(elkedge.getTargets().get(0));
            
            boolean isToBeLaidOut = !elkedge.getProperty(LayeredOptions.NO_LAYOUT);
            boolean isInsideSelfLoop = enableInsideSelfLoops && elkedge.isSelfloop()
                    && elkedge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
            boolean connectsToChild = targetNode.getParent() == elkgraph;
            
            if (isToBeLaidOut && (connectsToChild || isInsideSelfLoop)) {
                transformEdge(elkedge, elkgraph, lgraph);
            }
        }
    }

    /**
     * Imports the graph hierarchy rooted at the given graph.
     * 
     * @param elkgraph
     *            graph to import.
     * @param lgraph
     *            graph to add the direct children of the current hierarchy level to.
     */
    private void importHierarchicalGraph(final ElkNode elkgraph, final LGraph lgraph) {
        final Queue<ElkNode> elknodeQueue = Lists.newLinkedList();
        
        Direction parentGraphDirection = lgraph.getProperty(LayeredOptions.DIRECTION);

        // Transform the node's children
        elknodeQueue.addAll(elkgraph.getChildren());
        while (!elknodeQueue.isEmpty()) {
            ElkNode elknode = elknodeQueue.poll();
            
            // Check if the current node is to be laid out in the first place
            boolean isNodeToBeLaidOut = !elknode.getProperty(LayeredOptions.NO_LAYOUT);
            if (isNodeToBeLaidOut) {
                // Transform da node!!!
                LGraph parentLGraph = lgraph;
                LNode parentLNode = (LNode) nodeAndPortMap.get(elknode.getParent());
                if (parentLNode != null) {
                    parentLGraph = parentLNode.getProperty(InternalProperties.NESTED_LGRAPH);
                }
                LNode lnode = transformNode(elknode, parentLGraph);
                
                // Check if there has to be an LGraph for this node (which is the case if it has
                // children or inside self-loops)
                boolean hasChildren = !elknode.getChildren().isEmpty();
                boolean hasInsideSelfLoops = hasInsideSelfLoops(elknode);
                boolean hasHierarchyHandlingEnabled = elknode.getProperty(LayeredOptions.HIERARCHY_HANDLING)
                        == HierarchyHandling.INCLUDE_CHILDREN;

                if (hasHierarchyHandlingEnabled && (hasChildren || hasInsideSelfLoops)) {
                    LGraph nestedGraph = createLGraph(elknode);
                    nestedGraph.setProperty(LayeredOptions.DIRECTION, parentGraphDirection);
                    lnode.setProperty(InternalProperties.NESTED_LGRAPH, nestedGraph);
                    nestedGraph.setProperty(InternalProperties.PARENT_LNODE, lnode);
                    elknodeQueue.addAll(elknode.getChildren());
                }
            }
        }

        // Transform the edges
        elknodeQueue.add(elkgraph);
        while (!elknodeQueue.isEmpty()) {
            ElkNode elknode = elknodeQueue.poll();
            
            boolean enableInsideSelfLoops = elknode.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE);

            // Check if the current node is to be laid out in the first place
            if (!elknode.getProperty(LayeredOptions.NO_LAYOUT)) {
                for (ElkEdge elkedge : ElkGraphUtil.allOutgoingEdges(elknode)) {
                    // Check if the current edge is to be laid out
                    if (!elkedge.getProperty(LayeredOptions.NO_LAYOUT)) {
                        // We don't support hyperedges
                        checkEdgeValidity(elkedge);
                        
                        // Check if this edge is an inside self-loop
                        boolean isInsideSelfLoop = enableInsideSelfLoops
                                && elkedge.isSelfloop()
                                && elkedge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
                        
                        // Find the graph the edge will be placed in. Basically, if the edge is an inside
                        // self loop or connects to a descendant of this node, the edge will be placed in
                        // the graph that represents the node's insides. Otherwise, it will be placed in
                        // the graph that represents the node's parent.
                        ElkNode parentKGraph = elknode.getParent();
                        ElkNode edgeTargetNode = ElkGraphUtil.connectableShapeToNode(elkedge.getTargets().get(0));
                        
                        if (ElkGraphUtil.isDescendant(edgeTargetNode, elknode) || isInsideSelfLoop) {
                            parentKGraph = elknode;
                        }
                        
                        LGraph parentLGraph = lgraph;
                        LNode parentLNode = (LNode) nodeAndPortMap.get(parentKGraph);
                        if (parentLNode != null) {
                            parentLGraph = parentLNode.getProperty(InternalProperties.NESTED_LGRAPH);
                        }
                        
                        // Transform the edge, finally...
                        transformEdge(elkedge, parentKGraph, parentLGraph);
                    }
                }
                
                elknodeQueue.addAll(elknode.getChildren());
            }
        }
    }
    
    /**
     * Checks if the given node has any inside self loops.
     * 
     * @param elknode the node to check for inside self loops.
     * @return {@code true} if the node has inside self loops, {@code false} otherwise.
     */
    private boolean hasInsideSelfLoops(final ElkNode elknode) {
        if (elknode.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE)) {
            for (ElkEdge edge : ElkGraphUtil.allOutgoingEdges(elknode)) {
                if (edge.isSelfloop()) {
                    if (edge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Graph Transformation

    /**
     * Create an LGraph from the given KNode.
     * 
     * @param elkgraph
     *            the parent KNode from which to create the LGraph
     * @return a new LGraph instance
     */
    private LGraph createLGraph(final ElkNode elkgraph) {
        LGraph lgraph = new LGraph();
        
        // Copy the properties of the KGraph to the layered graph
        lgraph.copyProperties(elkgraph);
        if (lgraph.getProperty(LayeredOptions.DIRECTION) == Direction.UNDEFINED) {
            lgraph.setProperty(LayeredOptions.DIRECTION, LGraphUtil.getDirection(lgraph));
        }
        
        // The root may have a label manager installed
        if (lgraph.getProperty(LabelManagementOptions.LABEL_MANAGER) == null) {
            ElkGraphElement root = (ElkGraphElement) EcoreUtil.getRootContainer(elkgraph);
            lgraph.setProperty(LabelManagementOptions.LABEL_MANAGER,
                    root.getProperty(LabelManagementOptions.LABEL_MANAGER));
        }
        
        // Remember the KGraph parent the LGraph was created from
        lgraph.setProperty(InternalProperties.ORIGIN, elkgraph);

        // Initialize the graph properties discovered during the transformations
        lgraph.setProperty(InternalProperties.GRAPH_PROPERTIES,
                EnumSet.noneOf(GraphProperties.class));
        
        // Adjust the insets to respect inside labels.
        float labelSpacing = lgraph.getProperty(LayeredOptions.SPACING_LABEL_NODE);
        ElkPadding labelPadding = LabelSpaceCalculation.calculateRequiredNodeLabelSpace(
                ElkGraphAdapters.adaptSingleNode(elkgraph), labelSpacing);
        
        // Copy the insets to the layered graph
        LInsets linsets = lgraph.getInsets();
        linsets.left = labelPadding.left;
        linsets.right = labelPadding.right;
        linsets.top = labelPadding.top;
        linsets.bottom = labelPadding.bottom;
        
        // add the specified padding
        ElkPadding padding = lgraph.getProperty(LayeredOptions.PADDING);
        linsets.left += padding.getLeft();
        linsets.right += padding.getRight();
        linsets.top += padding.getTop();
        linsets.bottom += padding.getBottom();
        
        return lgraph;
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // External Port Transformation
    
    /**
     * Checks if external ports processing should be active. This is the case if the parent node has
     * ports and at least one of the following conditions is true:
     * <ul>
     *   <li>
     *     Port label placement is set to {@code INSIDE} and at least one of the ports has a label.
     *   </li>
     *   <li>
     *     At least one of the ports has an edge that connects to the insides of the parent node.
     *   </li>
     *   <li>
     *     There is a self-loop that should be routed inside the node.
     *   </li>
     * </ul>
     * 
     * @param elkgraph
     *            a KGraph we want to check for external ports.
     * @param graphProperties
     *            the set of graph properties to store our results in.
     */
    private void checkExternalPorts(final ElkNode elkgraph, final Set<GraphProperties> graphProperties) {
        final boolean enableSelfLoops = elkgraph.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE);
        final PortLabelPlacement portLabelPlacement = elkgraph.getProperty(LayeredOptions.PORT_LABELS_PLACEMENT);
        
        // We're iterating over the ports until we've determined that we have both external ports and
        // hyperedges, or if there are no more ports left
        boolean hasExternalPorts = false;
        boolean hasHyperedges = false;
        
        final Iterator<ElkPort> portIterator = elkgraph.getPorts().iterator();
        while (portIterator.hasNext() && (!hasExternalPorts || !hasHyperedges)) {
            final ElkPort elkport = portIterator.next();
            
            // Find out if there are edges connected to external ports of the graph (this is the case
            // for inside self loops as well as for edges connected to children)
            int externalPortEdges = 0;
            
            for (ElkEdge elkedge : ElkGraphUtil.allIncidentEdges(elkport)) {
                boolean isInsideSelfLoop = enableSelfLoops && elkedge.isSelfloop()
                        && elkedge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
                boolean connectsToChild = elkedge.getSources().contains(elkport)
                        ? elkgraph == ElkGraphUtil.connectableShapeToNode(elkedge.getTargets().get(0)).getParent()
                        : elkgraph == ElkGraphUtil.connectableShapeToNode(elkedge.getSources().get(0)).getParent();
                
                if (isInsideSelfLoop || connectsToChild) {
                    externalPortEdges++;
                    if (externalPortEdges > 1) {
                        break;
                    }
                }
            }
            
            // External ports?
            if (externalPortEdges > 0) {
                hasExternalPorts = true;
            } else if (portLabelPlacement == PortLabelPlacement.INSIDE && elkport.getLabels().size() > 0) {
                hasExternalPorts = true;
            }
            
            // Hyperedges, even?
            if (externalPortEdges > 1) {
                hasHyperedges = true;
            }
        }
        
        // Update graph properties
        if (hasExternalPorts) {
            graphProperties.add(GraphProperties.EXTERNAL_PORTS);
        }
        
        if (hasHyperedges) {
            graphProperties.add(GraphProperties.HYPEREDGES);
        }
    }

    /**
     * Transforms the given external port into a dummy node.
     * 
     * @param elkgraph
     *            the original KGraph
     * @param lgraph
     *            the corresponding layered graph
     * @param elkport
     *            the port to be transformed
     */
    private void transformExternalPort(final ElkNode elkgraph, final LGraph lgraph, final ElkPort elkport) {
        // We need some information about the port
        KVector kportPosition = new KVector(
                elkport.getX() + elkport.getWidth() / 2.0,
                elkport.getY() + elkport.getHeight() / 2.0);
        int netFlow = calculateNetFlow(elkport);
        PortSide portSide = elkport.getProperty(LayeredOptions.PORT_SIDE);
        PortConstraints portConstraints = elkgraph.getProperty(LayeredOptions.PORT_CONSTRAINTS);
        
        // If we don't have a proper port side, calculate one
        Direction layoutDirection = lgraph.getProperty(LayeredOptions.DIRECTION);
        if (portSide == PortSide.UNDEFINED) {
            portSide = ElkUtil.calcPortSide(elkport, layoutDirection);
            elkport.setProperty(LayeredOptions.PORT_SIDE, portSide);
        }
        
        // If we don't have a port offset, infer one
        Double portOffset = elkport.getProperty(LayeredOptions.PORT_BORDER_OFFSET);
        if (portOffset == null) {
            // if port coordinates are (0,0), we default to port offset 0 to make the common case frustration-free
            if (elkport.getX() == 0.0 && elkport.getY() == 0.0) {
                portOffset = 0.0;
            } else {
                portOffset = ElkUtil.calcPortOffset(elkport, portSide);
            }
            elkport.setProperty(LayeredOptions.PORT_BORDER_OFFSET, portOffset);
        }
        
        // Create the external port dummy node
        KVector graphSize = new KVector(elkgraph.getWidth(), elkgraph.getHeight());
        LNode dummy = LGraphUtil.createExternalPortDummy(
                elkport, portConstraints, portSide, netFlow, graphSize,
                kportPosition, new KVector(elkport.getWidth(), elkport.getHeight()),
                layoutDirection, lgraph);
        dummy.setProperty(InternalProperties.ORIGIN, elkport);
        
        // If the compound node wants to have its port labels placed on the inside, we need to leave
        // enough space for them by creating an LLabel for the KLabels
        if (elkgraph.getProperty(LayeredOptions.PORT_LABELS_PLACEMENT) == PortLabelPlacement.INSIDE) {
            // The dummy only has one port
            LPort dummyPort = dummy.getPorts().get(0);
            dummy.setProperty(LayeredOptions.PORT_LABELS_PLACEMENT, PortLabelPlacement.OUTSIDE);
            
            // Transform all of the port's labels
            for (ElkLabel elklabel : elkport.getLabels()) {
                if (!elklabel.getProperty(LayeredOptions.NO_LAYOUT)) {
                    dummyPort.getLabels().add(transformLabel(elklabel));
                }
            }
        }
        
        // Put the external port dummy into our graph and associate it with the original KPort
        lgraph.getLayerlessNodes().add(dummy);
        nodeAndPortMap.put(elkport, dummy);
    }
    
    /**
     * Count how many edges want the port to be an output port of the parent and how many want it to
     * be an input port. An edge coming into the port from the inside votes for the port to be an
     * output port of the parent, as does an edge leaving the port for the outside. The result returned
     * by this method is the so-called net flow as fed into {@code createExternalPort(..)}.
     * 
     * @param elkport
     *            the port to look at.
     * @return the port's net flow.
     */
    private int calculateNetFlow(final ElkPort elkport) {
        final ElkNode elkgraph = elkport.getParent();
        final boolean insideSelfLoopsEnabled = elkgraph.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_ACTIVATE);

        int outputPortVote = 0, inputPortVote = 0;
        
        // Iterate over outgoing edges
        for (ElkEdge outgoingEdge : elkport.getOutgoingEdges()) {
            final boolean isSelfLoop = outgoingEdge.isSelfloop();
            final boolean isInsideSelfLoop = isSelfLoop && insideSelfLoopsEnabled
                    && outgoingEdge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
            final ElkNode targetNode = ElkGraphUtil.connectableShapeToNode(outgoingEdge.getTargets().get(0));

            if (isSelfLoop && isInsideSelfLoop) {
                inputPortVote++;
            } else if (isSelfLoop && !isInsideSelfLoop) {
                outputPortVote++;
            } else if (targetNode.getParent() == elkgraph ||targetNode  == elkgraph) {
                inputPortVote++;
            } else {
                outputPortVote++;
            }
        }
        
        // Iterate over incoming edges
        for (ElkEdge incomingEdge : elkport.getIncomingEdges()) {
            final boolean isSelfLoop = incomingEdge.isSelfloop();
            final boolean isInsideSelfLoop = isSelfLoop && insideSelfLoopsEnabled
                    && incomingEdge.getProperty(LayeredOptions.INSIDE_SELF_LOOPS_YO);
            final ElkNode sourceNode = ElkGraphUtil.connectableShapeToNode(incomingEdge.getSources().get(0));

            if (isSelfLoop && isInsideSelfLoop) {
                outputPortVote++;
            } else if (isSelfLoop && !isInsideSelfLoop) {
                inputPortVote++;
            } else if (sourceNode.getParent() == elkgraph ||sourceNode  == elkgraph) {
                outputPortVote++;
            } else {
                inputPortVote++;
            }
        }
        
        return outputPortVote - inputPortVote;
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Node Transformation

    /**
     * Transforms the given node and its contained ports.
     * 
     * @param elknode
     *            the node to transform
     * @param lgraph
     *            the layered graph into which the transformed node is put
     * @return the transformed node
     */
    private LNode transformNode(final ElkNode elknode, final LGraph lgraph) {
        // add a new node to the layered graph, copying its position
        LNode lnode = new LNode(lgraph);
        lnode.copyProperties(elknode);
        lnode.setProperty(InternalProperties.ORIGIN, elknode);
        
        lnode.getSize().x = elknode.getWidth();
        lnode.getSize().y = elknode.getHeight();
        lnode.getPosition().x = elknode.getX();
        lnode.getPosition().y = elknode.getY();
        
        lgraph.getLayerlessNodes().add(lnode);
        nodeAndPortMap.put(elknode, lnode);
        
        // check if the node is a compound node in the original graph
        if (!elknode.getChildren().isEmpty()) {
            lnode.setProperty(InternalProperties.COMPOUND_NODE, true);
        }

        Set<GraphProperties> graphProperties = lgraph.getProperty(InternalProperties.GRAPH_PROPERTIES);
        
        // port constraints and sides cannot be undefined
        PortConstraints portConstraints = lnode.getProperty(LayeredOptions.PORT_CONSTRAINTS);
        if (portConstraints == PortConstraints.UNDEFINED) {
            lnode.setProperty(LayeredOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
        } else if (portConstraints != PortConstraints.FREE) {
            // if the port constraints are not free, set the appropriate graph property
            graphProperties.add(GraphProperties.NON_FREE_PORTS);
        }

        // transform the ports
        Direction direction = lgraph.getProperty(LayeredOptions.DIRECTION);
        for (ElkPort elkport : elknode.getPorts()) {
            if (!elkport.getProperty(LayeredOptions.NO_LAYOUT)) {
                transformPort(elkport, lnode, graphProperties, direction, portConstraints);
            }
        }

        // add the node's labels
        for (ElkLabel elklabel : elknode.getLabels()) {
            if (!elklabel.getProperty(LayeredOptions.NO_LAYOUT)) {
                lnode.getLabels().add(transformLabel(elklabel));
            }
        }

        if (lnode.getProperty(LayeredOptions.COMMENT_BOX)) {
            graphProperties.add(GraphProperties.COMMENTS);
        }

        // if we have a hypernode without ports, create a default input and output port
        if (lnode.getProperty(LayeredOptions.HYPERNODE)) {
            graphProperties.add(GraphProperties.HYPERNODES);
            graphProperties.add(GraphProperties.HYPEREDGES);
            lnode.setProperty(LayeredOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
        }
        
        return lnode;
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Port Transformation
    
    /**
     * Transforms the given port. The new port will be added to the given node and will be
     * registered with the {@code transformMap}.
     * 
     * @param elkport
     *            the port to transform.
     * @param parentLNode
     *            the node the port should be added to.
     * @param graphProperties
     *            the graph properties of the graph the transformed port will be part of. The graph
     *            properties are modified depending on the port's properties.
     * @param layoutDirection
     *            the layout direction in the graph the port will be part of.
     * @param portConstraints
     *            the port constraints of the port's node.
     * @return the transformed port.
     */
    private LPort transformPort(final ElkPort elkport, final LNode parentLNode,
            final Set<GraphProperties> graphProperties, final Direction layoutDirection,
            final PortConstraints portConstraints) {
        
        // create layered port, copying its position
        LPort lport = new LPort();
        lport.copyProperties(elkport);
        lport.setSide(elkport.getProperty(LayeredOptions.PORT_SIDE));
        lport.setProperty(InternalProperties.ORIGIN, elkport);
        lport.setNode(parentLNode);
        
        KVector portSize = lport.getSize();
        portSize.x = elkport.getWidth();
        portSize.y = elkport.getHeight();
        
        KVector portPos = lport.getPosition();
        portPos.x = elkport.getX();
        portPos.y = elkport.getY();
        
        nodeAndPortMap.put(elkport, lport);
        
        // check if the original port has any outgoing connections to descendants of its node
        boolean connectionsToDescendants = elkport.getOutgoingEdges().stream()
             // All targets of each edge
            .flatMap(edge -> edge.getTargets().stream())
             // Target connectable shapes to nodes
            .map(ElkGraphUtil::connectableShapeToNode)
            // Check if any target is a descendant of the port's parent node
            .anyMatch(targetNode -> ElkGraphUtil.isDescendant(targetNode, elkport.getParent()));
        
        // there could be yet incoming connections from descendants
        if (!connectionsToDescendants) {
            // check if the original port has any incoming connections from descendants of its node
            connectionsToDescendants = elkport.getIncomingEdges().stream()
                 // All sources of each edge
                .flatMap(edge -> edge.getSources().stream())
                 // Source connectable shapes to nodes
                .map(ElkGraphUtil::connectableShapeToNode)
                // Check if any source is a descendant of the port's parent node
                .anyMatch(sourceNode -> ElkGraphUtil.isDescendant(sourceNode, elkport.getParent()));
        }
        
        // if we have found connections to / from descendants, mark the port accordingly
        lport.setProperty(InternalProperties.INSIDE_CONNECTIONS, true);

        // initialize the port's side, offset, and anchor point
        LGraphUtil.initializePort(lport, portConstraints, layoutDirection,
                elkport.getProperty(LayeredOptions.PORT_ANCHOR));

        // create the port's labels
        for (ElkLabel elklabel : elkport.getLabels()) {
            if (!elklabel.getProperty(LayeredOptions.NO_LAYOUT)) {
                lport.getLabels().add(transformLabel(elklabel));
            }
        }

        // Check if we need to add anything to the graph properties
        if (elkport.getIncomingEdges().size() + elkport.getOutgoingEdges().size() > 1) {
            graphProperties.add(GraphProperties.HYPEREDGES);
        }
        
        switch (layoutDirection) {
        case LEFT:
        case RIGHT:
            if (lport.getSide() == PortSide.NORTH || lport.getSide() == PortSide.SOUTH) {
                graphProperties.add(GraphProperties.NORTH_SOUTH_PORTS);
            }
            break;
        case UP:
        case DOWN:
            if (lport.getSide() == PortSide.EAST || lport.getSide() == PortSide.WEST) {
                graphProperties.add(GraphProperties.NORTH_SOUTH_PORTS);
            }
            break;
        }
        
        return lport;
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Edge Transformation

    /**
     * Transforms the given edge if it's not a hyperedge. If it is a hyperedge, throws an exception.
     * 
     * @param elkedge the edge to transform
     * @param elkparent the node in the original graph which currently gets transformed into {@code lgraph}
     * @param lgraph the layered graph
     * @return the transformed edge, or {@code null} if it cannot be transformed
     * @throws UnsupportedGraphException if the edge is a hyperedge.
     */
    private LEdge transformEdge(final ElkEdge elkedge, final ElkNode elkparent, final LGraph lgraph) {
        checkEdgeValidity(elkedge);
        
        // Get a few basic information about the edge
        ElkConnectableShape elkSourceShape = elkedge.getSources().get(0);
        ElkConnectableShape elkTargetShape = elkedge.getTargets().get(0);
        ElkNode elkSourceNode = ElkGraphUtil.connectableShapeToNode(elkSourceShape);
        ElkNode elkTargetNode = ElkGraphUtil.connectableShapeToNode(elkTargetShape);
        
        ElkEdgeSection edgeSection = elkedge.getSections().isEmpty() ? null : elkedge.getSections().get(0);
        
        // Find the transformed source and target nodes
        LNode sourceLNode = (LNode) nodeAndPortMap.get(elkSourceNode);
        LNode targetLNode = (LNode) nodeAndPortMap.get(elkTargetNode);
        LPort sourceLPort = null;
        LPort targetLPort = null;

        // Find the transformed source port, if any
        if (elkSourceShape instanceof ElkPort) {
            // If the KPort is a regular port, it will map to an LPort; if it's an external port, it
            // will map to an LNode
            LGraphElement sourceElem = nodeAndPortMap.get(elkSourceShape);
            if (sourceElem instanceof LPort) {
                sourceLPort = (LPort) sourceElem;
            } else if (sourceElem instanceof LNode) {
                sourceLNode = (LNode) sourceElem;
                sourceLPort = sourceLNode.getPorts().get(0);
            }
        }

        // Find the transformed target port, if any
        if (elkTargetShape instanceof ElkPort) {
            // If the KPort is a regular port, it will map to an LPort; if it's an external port, it
            // will map to an LNode
            LGraphElement targetElem = nodeAndPortMap.get(elkTargetShape);
            if (targetElem instanceof LPort) {
                targetLPort = (LPort) targetElem;
            } else if (targetElem instanceof LNode) {
                targetLNode = (LNode) targetElem;
                targetLPort = targetLNode.getPorts().get(0);
            }
        }
        
        // If either the source or the target of the edge wasn't properly transformed for whatever
        // reason, we back out
        if (sourceLNode == null || targetLNode == null) {
            return null;
        }
        
        // Create a layered edge
        LEdge ledge = new LEdge();
        ledge.copyProperties(elkedge);
        ledge.setProperty(InternalProperties.ORIGIN, elkedge);
        
        // Clear junction points, since they are recomputed from scratch
        ledge.setProperty(LayeredOptions.JUNCTION_POINTS, null);
        
        // If we have a self-loop, set the appropriate graph property
        Set<GraphProperties> graphProperties = lgraph.getProperty(InternalProperties.GRAPH_PROPERTIES);
        if (sourceLNode == targetLNode) {
            graphProperties.add(GraphProperties.SELF_LOOPS);
        }

        // Create source and target ports if they do not exist yet
        if (sourceLPort == null) {
            PortType portType = PortType.OUTPUT;
            KVector sourcePoint = null;
            
            if (sourceLNode.getProperty(LayeredOptions.PORT_CONSTRAINTS).isSideFixed()) {
                sourcePoint = new KVector(edgeSection.getStartX(), edgeSection.getStartY());
                
                // The coordinates need to be relative to us
                ElkUtil.toAbsolute(sourcePoint, elkedge.getContainingNode());
                ElkUtil.toRelative(sourcePoint, elkparent);
                
                // If the edge is hierarchical (in which case it can only be that the target is a descendant of the
                // source), we may need to adjust the coordinates
                if (ElkGraphUtil.isDescendant(elkTargetNode, elkSourceNode)) {
                    // External source port: put it on the west side
                    portType = PortType.INPUT;
                    sourcePoint.add(sourceLNode.getPosition());
                }
            }
            sourceLPort = LGraphUtil.createPort(sourceLNode, sourcePoint, portType, lgraph);
        }
        
        if (targetLPort == null) {
            PortType portType = PortType.INPUT;
            KVector targetPoint = null;
            
            if (edgeSection != null && targetLNode.getProperty(LayeredOptions.PORT_CONSTRAINTS).isSideFixed()) {
                targetPoint = new KVector(edgeSection.getEndX(), edgeSection.getEndY());
                
                // Adjust the coordinates
                // MIGRATE Not sure yet if this really does what we want it to do
                ElkUtil.toAbsolute(targetPoint, elkedge.getContainingNode());
                ElkUtil.toRelative(targetPoint, elkparent);
            }
            
            targetLPort = LGraphUtil.createPort(targetLNode, targetPoint, portType, targetLNode.getGraph());
        }
        
        // Finally set the source and target of the edge
        ledge.setSource(sourceLPort);
        ledge.setTarget(targetLPort);

        // Transform the edge's labels
        for (ElkLabel elklabel : elkedge.getLabels()) {
            if (!elklabel.getProperty(LayeredOptions.NO_LAYOUT)) {
                LLabel llabel = transformLabel(elklabel);
                ledge.getLabels().add(llabel);
                
                // Depending on the label placement, we want to set graph properties and make sure the
                // edge label placement is actually properly defined
                switch (llabel.getProperty(LayeredOptions.EDGE_LABELS_PLACEMENT)) {
                case HEAD:
                case TAIL:
                    graphProperties.add(GraphProperties.END_LABELS);
                    break;
                    
                case CENTER:
                case UNDEFINED:
                    graphProperties.add(GraphProperties.CENTER_LABELS);
                    llabel.setProperty(LayeredOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER);
                }
            }
        }
        
        // Copy the original bend points of the edge in case they are required
        CrossingMinimizationStrategy crossMinStrat = lgraph.getProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY);
        NodePlacementStrategy nodePlaceStrat = lgraph.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY);
        boolean bendPointsRequired = crossMinStrat == CrossingMinimizationStrategy.INTERACTIVE
                || nodePlaceStrat == NodePlacementStrategy.INTERACTIVE;
        
        if (edgeSection != null && !edgeSection.getBendPoints().isEmpty() && bendPointsRequired) {
            KVectorChain originalBendpoints = ElkUtil.createVectorChain(edgeSection);
            KVectorChain importedBendpoints = new KVectorChain();
            
            // MIGRATE We may have to do some coordinate conversion here
            for (KVector point : originalBendpoints) {
                importedBendpoints.add(new KVector(point));
            }
            ledge.setProperty(InternalProperties.ORIGINAL_BENDPOINTS, importedBendpoints);
        }
        
        return ledge;
    }
    
    /**
     * Checks if the given edge has exactly one source and one parent.
     * 
     * @param edge the edge to check.
     * @throws UnsupportedGraphException if the edge does not meet the criteria.
     */
    private void checkEdgeValidity(final ElkEdge edge) {
        if (edge.getSources().isEmpty()) {
            throw new UnsupportedGraphException("Edges must have a source.");
        } else if (edge.getTargets().isEmpty()) {
            throw new UnsupportedGraphException("Edges must have a target.");
        } else if (edge.isHyperedge()) {
            throw new UnsupportedGraphException("Hyperedges are not supported.");
        }
    }
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Label Transformation

    /**
     * Transform the given {@code ElkLabel} into an {@code LLabel}.
     * 
     * @param elklabel the label to transform.
     * @return the created {@code LLabel}.
     */
    private LLabel transformLabel(final ElkLabel elklabel) {
        LLabel newLabel = new LLabel(elklabel.getText());
        
        newLabel.copyProperties(elklabel);
        newLabel.setProperty(InternalProperties.ORIGIN, elklabel);
        
        newLabel.getSize().x = elklabel.getWidth();
        newLabel.getSize().y = elklabel.getHeight();
        newLabel.getPosition().x = elklabel.getX();
        newLabel.getPosition().y = elklabel.getY();
        
        return newLabel;
    }

}