package apoc.cfgPath;

import apoc.algo.CFGShortestPath;
import apoc.util.Util;
import org.neo4j.graphalgo.BasicEvaluationContext;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.util.*;

public class ROSPath {

    @Context
    public GraphDatabaseService db;

    @Context
    public Transaction tx;

    @UserFunction
    @Description("apoc.cfgPath.rosFindPaths(start, [settings])")
    public List<Path> rosFindPaths(@Name("start") Object start, @Name("config") Map<String,Object> config) {
        // configuration graph variables
        Node startNode = null;
        Relationship startEdge = null;
        Node endNode = (Node) config.getOrDefault("endN", null);
        Relationship endEdge = (Relationship) config.getOrDefault("endE", null);

        // process starting points - has to be either a starting node or edge
        if (start == null) {
            return null;
        } else if (start instanceof Node) {
            startNode = (Node) start;
        } else if (start instanceof Relationship) {
            startEdge = (Relationship) start;
        } else {
            return null;
        }

        // read other settings configuration
        // Accepted parameters:
        //    - cfg: whether or not to perform cfg check (default: true)
        //    - relSeq: relationship pattern in return path (default: null)
        //    - repeat: whether or not above relSequence repeats (default: false)
        //    - backward: find path with a backward search (default: false)
        //    - shortest: whether or not we return shortest path or all paths (default: false)
        //    - filter: accepted nodes in our shortest path (default: null)
        //    - config: describes how the source and destination CFG blocks relate to each other
        //          for a particular type of relation
        //    - isStartEdgeValid: is first edge consider a valid path (default: false)
        boolean cfgCheck = Util.toBoolean(config.getOrDefault("cfg", true));
        String relSequence = (String) config.getOrDefault("relSeq", null);
        boolean repeat = Util.toBoolean(config.getOrDefault("repeat", false));
        boolean backward = Util.toBoolean(config.getOrDefault("backward", false));
        boolean allShortestPath = Util.toBoolean(config.getOrDefault("shortest", false));
        List<Map<String, Object>> cfgConfigurationList =
                (List<Map<String,Object>>) config.getOrDefault("config", null);
        HashMap<String, CFGSetting> cfgConfig = parseCFGConfiguration(cfgConfigurationList);
        RelExtension extension = new RelExtension(relSequence, repeat, backward);
        HashSet<Label> acceptedNodes = filterNodes((String) config.getOrDefault("filter", null));
        boolean isStartEdgeValid = Util.toBoolean(config.getOrDefault("isStartEdgeValid", false));

        if (backward) {
            return findPath(endNode, startNode, endEdge, startEdge, cfgConfig, extension, allShortestPath, cfgCheck,
                    acceptedNodes, backward, isStartEdgeValid);
        } else {
            return findPath(startNode, endNode, startEdge, endEdge, cfgConfig, extension, allShortestPath, cfgCheck,
                    acceptedNodes, backward, isStartEdgeValid);
        }



    }

    // helper function: find path
    public List<Path> findPath(Node startNode, Node endNode, Relationship startEdge, Relationship endEdge,
                               HashMap<String, CFGSetting> cfgConfig, RelExtension extension,
                               boolean allShortestPath, boolean cfgCheck, HashSet<Label> acceptedNodes,
                               boolean backward, boolean isStartEdgeValid) {

        // variables
        List<BasicCandidatePath> returnPaths = new ArrayList<>();
        HashSet<Relationship> visitedEdges = new HashSet<>();
        Queue<BasicCandidatePath> queuePath = new LinkedList<>();
        Node start = startNode;
        Node end = endNode;
        ArrayList<ArrayList<RelationshipType>> curType;
        Iterable<Relationship> nextRels;
        BasicCandidatePath curPath = null;

        // Start edge not null, reassign start node with its ending node
        if (startEdge != null) {
            start = (backward) ? startEdge.getStartNode() : startEdge.getEndNode();
            curPath = new BasicCandidatePath(startEdge, -1);
            if (cfgCheck) {updateFirstCFGNodes(curPath, cfgConfig, backward);} // update CFG related nodes
            queuePath.add(curPath);
            if (allShortestPath) {visitedEdges.add(startEdge);} // update visited nodes
        }

        // End edge not null, reassign end node with its starting node
        if (endEdge != null) {
            end = (backward) ? endEdge.getEndNode() : endEdge.getStartNode();
        }

        // if we don't have a start node, then return none
        if (start == null) {
            return null;
        }

        if ((isStartEdgeValid) && (end == null) && (curPath != null)) {
            returnPaths.add(curPath);
        }


        // If we don't have start edge, then attempt to get first edge in candidate path
        if (startEdge == null) {

            // use relationship sequence 0
            curType = extension.constructTypes(0);
            int y = 0;

            // For each possible sequences, find next possible candidates
            for (ArrayList<RelationshipType> curT : curType) {
                nextRels = start.getRelationships(Direction.OUTGOING,
                        curT.toArray(RelationshipType[]::new));
                for (Relationship nextRel : nextRels) {
                    Node nextNode = (backward) ? nextRel.getStartNode() : nextRel.getEndNode();
                    if ((acceptedNodes != null) &&
                            (!acceptedNodes.contains(nextNode.getLabels().iterator().next()))) {
                        continue;
                    }

                    // only create path if we are looking for all path or it is not in visited edges
                    if ((!allShortestPath) || (!visitedEdges.contains(nextRel))) {
                        curPath = new BasicCandidatePath(nextRel, y);
                        if (cfgCheck) {updateFirstCFGNodes(curPath, cfgConfig, backward);}
                        queuePath.add(curPath);
                    }

                }
                y += 1;
            }


        }

        BasicCandidatePath foundCandidatePath = null;
        ArrayList<ArrayList<Relationship>> retCovered = new ArrayList<>();
        HashSet<Relationship> visitedEdge = new HashSet<>();

        int pathLen = -1;
        boolean foundPath = false;

        // process each candidate path and verify that it is valid before proceeding the search
        while (!queuePath.isEmpty()) {
            curPath = queuePath.remove();

            // If we already found something, check if we want to proceed by checking return edge
            // combinations
            if ((allShortestPath) && (foundCandidatePath != null)) {
                visitedEdges.addAll(visitedEdge);
                if ((!curPath.compareRetNodes(foundCandidatePath))) {
                    continue;
                } else {
                    if (retCovered.contains(curPath.retRel)) {
                        continue;
                    }
                }
            }

            int curLen = curPath.getPathSize();
            if (retCovered.isEmpty() && allShortestPath && foundPath && curLen > pathLen) {
                // if path has been found and current path is longer than found path, can break
                break;
            }

            if (allShortestPath && curLen > pathLen) {
                // add all relationships found at previous path length to visitedRels
                visitedEdges.addAll(visitedEdge);
                visitedEdge = new HashSet<Relationship>();
            }
            pathLen = curLen;

            // Make sure it passes the CFG test before proceeding to look further
            if ((!cfgCheck) || getCFGPath(curPath, cfgConfig, backward)) {

                // Only add to visitedEdges if we are looking for shortest path
                if (allShortestPath) {visitedEdge.add(curPath.getLastEdge()); }

                // Add to return path only if the following conditions are met:
                //      - Matches last edge type of relationship
                //      - If has end node/end edge, also needs to match that
                Node compNode = backward ? curPath.getLastEdge().getStartNode() : curPath.getLastEdge().getEndNode();
                if ((extension.isEndIndex(curPath.getPathIndex())) &&
                        ((end == null) || (compNode.equals(end)))) {
                    // need to also pass CFG test if there is an end edge
                    if (endEdge != null) {
                        BasicCandidatePath tempPath = new BasicCandidatePath(curPath, endEdge,
                                curPath.pathIndex, backward);
                        if ((!cfgCheck) || getCFGPath(tempPath, cfgConfig, backward)) {
                            returnPaths.add(tempPath);
                            if (allShortestPath) {
                                foundCandidatePath = curPath;
                                retCovered.addAll(tempPath.getRetComp());
                                foundPath = true;
                                continue;
                            }
                        }
                    // otherwise we can just add to return path list
                    } else {
                        returnPaths.add(curPath);
                        if (allShortestPath) {
                            foundCandidatePath = curPath;
                            retCovered.addAll(curPath.getRetComp());
                            foundPath = true;
                            continue;
                        }
                    }
                }

                // Attempt to get next possible edge
                int index = extension.nextIndex(curPath.getLastEdge().getType(), curPath.getPathIndex());
                if (index <= extension.lastIndex) {
                    curType = extension.constructTypes(index);
                    int i = 0;
                    for (ArrayList<RelationshipType> curT : curType) {
                        Node curNode = backward ? curPath.getLastEdge().getStartNode() :
                                curPath.getLastEdge().getEndNode();
                        Direction dir = backward ? Direction.INCOMING : Direction.OUTGOING;
                        nextRels = curNode.getRelationships(dir,
                                curT.toArray(RelationshipType[]::new));
                        for (Relationship nextRel : nextRels) {
                            Node nextNode = (backward) ? nextRel.getStartNode() : nextRel.getEndNode();
                            if ((acceptedNodes != null) &&
                                    (!acceptedNodes.contains(nextNode.getLabels().iterator().next()))) {
                                continue;
                            }

                            // add to candidate path if only the following conditions are met
                            //      - if all shortestPath: visited edge does not contain nextRel
                            //      - if look for all path, then current path does not contain nextRel
                            boolean addPath = ((allShortestPath) && (!visitedEdges.contains(nextRel))) ||
                                    ((!allShortestPath) && (!curPath.getPath().contains(nextRel)));

                            if (addPath) {
                                BasicCandidatePath newCandidatePath = new BasicCandidatePath(curPath, nextRel,
                                        index+i, backward);
                                queuePath.add(newCandidatePath);
                            }
                        }
                        i += 1;
                    }

                }

            }

        }

        // Convert array to relationships to actual paths before returning
        ArrayList<Path> paths = new ArrayList<>();
        for (BasicCandidatePath path : returnPaths) {
            if (backward) {
                paths.add(path.reversebuildPath());
            } else {
                paths.add(path.buildPath());
            }
        }

        return paths;
    }

    // helper function: adding destination CFG nodes to first edge in path
    private void updateFirstCFGNodes(BasicCandidatePath path, HashMap<String, CFGSetting> config, boolean backward) {
        HashSet<List<Node>> endCFGs = CFGValidationHelper.getConnectionNodesAll(path.getLastEdge(), config);
        HashSet<Node> endNodes = new HashSet<>();
        for (List<Node> endCFG : endCFGs) {
            endNodes.add(backward ? endCFG.get(0) : endCFG.get(1));
        }
        path.setValidCFGs(endNodes);
    }

    // helper function: parse how the source and destination CFG nodes relate to each other
    private HashMap<String, CFGSetting> parseCFGConfiguration(List<Map<String, Object>> cfgConfigList) {
        HashMap<String, CFGSetting> cfgConfig = new HashMap<>();

        if (cfgConfigList == null) {
            return cfgConfig;
        }

        // loop through each map item
        for (Map<String, Object> cfgConfigItem : cfgConfigList) {
            // type of relationship
            String relType = (String) cfgConfigItem.getOrDefault("name", null);
            if (relType == null) {continue;} // skip if no name provided

            // get start node type and end node type
            String startLabel = (String) cfgConfigItem.getOrDefault("startLabel", null);
            String endLabel = (String) cfgConfigItem.getOrDefault("endLabel", null);

            // get attribute and length
            // default for length is none: indicating source and destination CFG block are the same
            // e.g. {attribute : "cfgInvoke,cfgReturn", length : "2"}
            //      this means that there are two nextCFGBlock relation between source and destination
            //       CFG node where the first relation has the attribute cfgInvoke and the second relation
            //       has the attribute cfgReturn
            String attribute = (String) cfgConfigItem.getOrDefault("attribute", null);
            String length = (String) cfgConfigItem.getOrDefault("length", 0);
            cfgConfig.put(startLabel + relType + endLabel, new CFGSetting(length, attribute));
        }

        return cfgConfig;

    }

    // helper function: get CFG nodes for last edge in path, and check if it is connected to CFG node up
    //      to second last edge in path
    public boolean getCFGPath(BasicCandidatePath path, HashMap<String, CFGSetting> config, boolean backward) {
        // in case there is only one edge in path, then the cfg path always passes
        if (path.getPathSize() < 2) {
            return true;
        }

        // get last edge and the CFG node related to the second last edge
        Relationship condEdge = (backward) ? path.getSecondLastEdge() : path.getLastEdge();
        Relationship lastEdge = path.getLastEdge();
        HashSet<Node> prevCFGs = path.getValidCFGs(); // nodes of subpath

        // create CFG shortest path object
        CFGShortestPath shortestPath = new CFGShortestPath(
                new BasicEvaluationContext(tx, db),
                (int) Integer.MAX_VALUE,
                CFGValidationHelper.buildPathExpander("nextCFGBlock>"));

        // get the corresponding CFG node for last edge in path
        HashSet<List<Node>> curCFGs = CFGValidationHelper.getConnectionNodesAll(lastEdge, config); // nodes of new edge
        HashSet<Node> acceptedNewCFG = new HashSet<>();

        // attempt to find a directed path between CFG nodes from path up to second last edge to last edge
        for (Node prevCFG : prevCFGs) {   // nodes of subpath
            for (List<Node> curCFG : curCFGs) {   // nodes of new edge
                // Node curNode = prevCFG.get(0);
                Node startCFG = (backward) ? curCFG.get(1) : prevCFG;
                Node dstNode = (backward) ? prevCFG : curCFG.get(0);
                Path cfgPath = shortestPath.findSinglePath(startCFG, dstNode, condEdge);
                if (cfgPath != null) { // if found, then we add to accepted CFG nodes
                    acceptedNewCFG.add(backward ? curCFG.get(0) : curCFG.get(1));
                }
            }
        }

        // update the accepted CFG nodes in path and return whether or not CFG test passes
        path.setValidCFGs(acceptedNewCFG);
        return !acceptedNewCFG.isEmpty();

    }

    // helper function: extract accepted nodes and store them in a hashset
    public HashSet<Label> filterNodes(String acceptNodesStr) {
        if (acceptNodesStr == null) {
            return null;
        }

        HashSet<Label> acceptedNodes = new HashSet<>();

        for (String acceptedNodeStr : acceptNodesStr.split(",")) {
            Label acceptedNode = Label.label(acceptedNodeStr);
            acceptedNodes.add(acceptedNode);
        }

        return acceptedNodes;
    }

}
