package edu.brown.cs.sdn.apps.sps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections; // added
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet; // added
import java.util.Set; // added
import java.util.PriorityQueue; // added
import java.util.LinkedList; // added

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.action.OFActionOutput; // added all below
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.OFMatchField;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;

import edu.brown.cs.sdn.apps.util.Host;
import edu.brown.cs.sdn.apps.util.SwitchCommands; // added

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.packet.Ethernet; //added

public class ShortestPathSwitching implements IFloodlightModule, IOFSwitchListener, 
		ILinkDiscoveryListener, IDeviceListener, InterfaceShortestPathSwitching
{
	public static final String MODULE_NAME = ShortestPathSwitching.class.getSimpleName();
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to link discovery service
    private ILinkDiscoveryService linkDiscProv;

    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Map of hosts to devices
    private Map<IDevice,Host> knownHosts;

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary for shortest */
        /*********************************************************************/

	}
	// helpers to make sure my brain doesn't explode
	// this one creates order in a land of chaos for routes to a specific host
	public void buildRules(Host h){
		// check if switch is in network
		if(h.isAttachedToSwitch()){
			IOFSwitch hostSwitch = h.getSwitch();
			OFMatch hostMatch = addMatch(h);
			// build rules to reach the host for each switch
			for(IOFSwitch s : getSwitches().values()){
				installCommand(h, s, hostMatch, this.table);	
			}
		}
	}
	// This one creates chaos for routs to a specific host
	public void destroyRules(Host h){
		OFMatch ofMatch = addMatch(h);
		for(IOFSwitch s : getSwitches().values()) {
			SwitchCommands.removeRules(s, this.table, ofMatch);
		}
	}
	// For all routs to all hosts
	public void destroyAllRules() {
		for(Host host : getHosts()) {
			destroyRules(host);
		}
	}
	// For all routs to all hosts
	public void buildAllRules(){
		for(Host host : getHosts()) {
			buildRules(host);
		}
	}


	OFMatch addMatch(Host host) { 
		OFMatch ofMatch = new OFMatch(); 
		OFMatchField etherType = new OFMatchField(OFOXMFieldType.ETH_TYPE,Ethernet.TYPE_IPv4); 
		OFMatchField macAddr = new 
			OFMatchField(OFOXMFieldType.ETH_DST,
			Ethernet.toByteArray(host.getMACAddress())); 
		ofMatch.setMatchFields(Arrays.asList(etherType, macAddr));
		return ofMatch;
	}


	/**
	 * my dijkstra's, it takes in a start and destination
	 * This is based off of the algorithm here
	 * https://www.geeksforgeeks.org/shortest-path-unweighted-graph/
	 * returns the next Switch in the shortest path between start and finish
	 * @param IOFSwitch start node
	 * @param IOFSwitch end node
	 */
	public IOFSwitch dijkstra(IOFSwitch start, IOFSwitch finish){
		
		Map<Long, IOFSwitch> vertices = getSwitches();

		Collection<Link> allLinks = getLinks();

		// hash map of ArrayList containing ID of nieghrbors of the corresponding IOFswitch
		HashMap<IOFSwitch, ArrayList<IOFSwitch>> adj = new HashMap<IOFSwitch, ArrayList<IOFSwitch>>();
		for (IOFSwitch node: vertices.values()) {
			adj.put(node, new ArrayList<IOFSwitch>());
			for(Link l: allLinks){
				if(l.getSrc() == node.getId()){
					adj.get(node).add(this.floodlightProv.getSwitch(l.getDst()));
				}
			}
		}
		// get the shortest path
		LinkedList<IOFSwitch> path =  shortestpath(adj, start, finish, vertices);
		// initialize first hope to first node
		IOFSwitch wheretosend =	path.get(path.size()-1);
		// then try catch to handle first hop out of bounds exception
		try {
			wheretosend = path.get(path.size()-2);
		} catch (Exception e) {}
		return wheretosend;

	}
	// maybe this one should actually be called dijkstra's...
	private LinkedList shortestpath(HashMap<IOFSwitch, ArrayList<IOFSwitch>> adj,
	IOFSwitch s, IOFSwitch dest, Map<Long, IOFSwitch> vertices){

		// predecessor(i) stores predecessor of
		// i and distance stores distance of i
		// from s	

		HashMap<IOFSwitch, IOFSwitch> pred = new HashMap<IOFSwitch, IOFSwitch>();
		HashMap<IOFSwitch, Long> distance = new HashMap<IOFSwitch, Long>();

		// Breath first Search populates pred, and distance
		if (BFS(adj, s, dest, vertices, pred, distance) == false) {
			log.info("Given source and destination are not connected");
		}
		//populate path from pred
		LinkedList<IOFSwitch> path = new LinkedList<IOFSwitch>();
		IOFSwitch crawl = dest;
		path.add(crawl);
		while (pred.get(crawl) != null) {
			path.add(pred.get(crawl));
			crawl = pred.get(crawl);
		}
		log.info("Shortest path length is: " + distance.get(dest).toString());
		log.info("Path is ::");
		for (int i = path.size() -1; i >= 0; i--) {
			if (i==0){
				log.info(String.format(path.get(i).getId() + "  then to host!"));
			} else {
				log.info(String.format(path.get(i).getId() + " --> "));
			}
		}
		return path;

	}
	private static boolean BFS(HashMap<IOFSwitch, ArrayList<IOFSwitch>> adj, IOFSwitch src,
	IOFSwitch dest, Map<Long, IOFSwitch> vertices, HashMap<IOFSwitch, IOFSwitch> pred, HashMap<IOFSwitch, Long> distance){

		// a queue to maintain queue of vertices whose
		// adjacency list is to be scanned as per normal
		// BFS algorithm using LinkedList of IOFSwitch type
		LinkedList<IOFSwitch> queue = new LinkedList<IOFSwitch>();
		HashMap<IOFSwitch, Boolean> visited = new HashMap<IOFSwitch, Boolean>();;

		for(IOFSwitch node: vertices.values()){
			if(node != src){
				visited.put(node, false);
				distance.put(node, (long) Long.MAX_VALUE);
			}
			pred.put(node, null);

		}
		// now source is first to be visited and
		// distance from source to itself should be 0
		visited.put(src, true);
		distance.put(src, (long) 0);
		queue.add(src);

		// bfs Algorithm
		while (!queue.isEmpty()) {
			IOFSwitch u = queue.remove();
			// HashMap<IOFSwitch, ArrayList<Long>> adj
			// loop through neighbors
			for (int i = 0; i < adj.get(u).size(); i++) {
				if (visited.get(adj.get(u).get(i)) == false) {
					visited.put(adj.get(u).get(i), true);
					distance.put(adj.get(u).get(i), distance.get(u) + 1);
					pred.put(adj.get(u).get(i), u);
					queue.add(adj.get(u).get(i));
					// stopping condition (when we find
					// our destination)
					if (adj.get(u).get(i) == dest)
						return true;
				}
			}
		}
		return false;

	}


	OFActionOutput getActionOutput(Host host, IOFSwitch currentSwitch) { 
		IOFSwitch hostSwitch = host.getSwitch();
		OFActionOutput ofActionOutput = new OFActionOutput(); 
		if (currentSwitch.getId() == hostSwitch.getId()) {
			ofActionOutput.setPort(host.getPort()); 
		} else {

			IOFSwitch next = dijkstra(currentSwitch, hostSwitch);
			log.info(String.format("Current Switch: s%d", currentSwitch.getId()));
			log.info(String.format("Host Switch: s%d", hostSwitch.getId()));
			log.info(String.format("Next Switch: s%d", next.getId()));
			// find right link between current switch and next switch
			for(Link l: getLinks()){
				if(l.getSrc() == currentSwitch.getId() && l.getDst() == next.getId()){
					// this is where my 18h logic problem was...
					ofActionOutput.setPort(l.getSrcPort()); // maybe change to SrcPort()
				}
			}
		}
		return ofActionOutput; 
	}
	void installCommand(Host host, IOFSwitch currentSwitch, OFMatch match, byte table) { 
		OFInstructionApplyActions actions = new
			OFInstructionApplyActions(Collections.<OFAction>singletonList( 
				getActionOutput(host, currentSwitch)));

		SwitchCommands.installRule(currentSwitch, table, 
			SwitchCommands.DEFAULT_PRIORITY, match,
			Collections.<OFInstruction>singletonList(actions));
	}
	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
	 * Get the table in which this application installs rules.
	 */
	public byte getTable()
	{ return this.table; }
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts()
    { return this.knownHosts.values(); }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches()
    { return floodlightProv.getAllSwitchMap(); }
	
    /**
     * Get a list of all active links in the network.
     */
    private Collection<Link> getLinks()
    { return linkDiscProv.getLinks().keySet(); }

    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) 
	{
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */
			/* add a match 
			/*****************************************************************/
			log.info(String.format("Host(host) %s has switch and port s%d:%d", 
			host.getName(),host.getSwitch().getId(), host.getPort()));
			buildRules(host);

		}
	}
	
	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		log.info(String.format("Host %s is no longer attached to a switch", 
				host.getName()));
		
		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */
		
		/*********************************************************************/
		destroyRules(host);
	}

	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch())
		{
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(),
				host.getSwitch().getId(), host.getPort()));
		
		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */
		
		/*********************************************************************/
		destroyRules(host);
		buildRules(host);
	}
	
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */

		/*********************************************************************/
		destroyAllRules();
		buildAllRules();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		/*********************************************************************/
		destroyAllRules();
		buildAllRules();
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) 
	{
		for (LDUpdate update : updateList)
		{
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst())
			{
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else
			{
				log.info(String.format("Link s%s:%d -> %s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		/*********************************************************************/
		buildAllRules();
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) 
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) 
	{ this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) 
	{ /* Nothing we need to do, since we're not using VLANs */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) 
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) 
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) 
	{ return false; }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{
		Collection<Class<? extends IFloodlightService>> services =
					new ArrayList<Class<? extends IFloodlightService>>();
		services.add(InterfaceShortestPathSwitching.class);
		return services; 
	}

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ 
        Map<Class<? extends IFloodlightService>, IFloodlightService> services =
        			new HashMap<Class<? extends IFloodlightService>, 
        					IFloodlightService>();
        // We are the class that implements the service
        services.put(InterfaceShortestPathSwitching.class, this);
        return services;
	}

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> modules =
	            new ArrayList<Class<? extends IFloodlightService>>();
		modules.add(IFloodlightProviderService.class);
		modules.add(ILinkDiscoveryService.class);
		modules.add(IDeviceService.class);
        return modules;
	}
}
