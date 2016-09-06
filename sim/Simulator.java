package slather.sim;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.tools.*;
import java.awt.Desktop;
import java.util.concurrent.*;

class Simulator {

    // root folder
    private static final String root = "slather";

    // timeouts
    private static long init_timeout = 1000;
    private static long play_timeout = 1000;

    // exit on player exception
    private static boolean exit_on_exception = false;

    // random generator
    private static Random random = new Random();

    // default sizes
    private static final int side_length = 100; //10cm

    // speeds
    private static final int move_dist = 1; //1mm

    // turn limit (infinite if negative)
    private static long turn_limit = -1;

    // turns after no reproduction has occurred
    private static long no_reproduce_turns = 100;

    // time per webgui frame
    private static int refresh = 100;

    // print messages to terminal
    private static boolean verbose = false;

    // group players
    private static String[] groups = {"g0","g0","g0","g0","g0","g0","g0","g0","g0","g0"};
    private static Class[] player_classes;
    private static Player[] players;
    private static int[] score;
    private static Timer[] threads;

    // default params
    private static int d = 10; // radius of vision of cell (in mm)
    private static int t = 50; // number of turns after which pherome wears out
    private static int p = 10; // number of players. default is ten copies of g0
    private static int n = 1; // number of starting cells per player

    private static ArrayList<Cell> cells = new ArrayList<Cell>();
    private static Set<Pherome> pheromes = new HashSet<Pherome>();

    private static int turns_without_reproduction = 0;
    
    
    private static boolean init() {
	for (int g=0; g<p; g++) {
	    for (int j=0; j<n; j++) {
		cells.add(new Cell( new Point(random.nextInt(side_length),random.nextInt(side_length)), g) );
	    }
	}

	// initialize players
	for (int g = 0 ; g < p ; g++) {
	    threads[g] = new Timer();
	    threads[g].start();
	    players[g] = null;
	    score[g] = 0;
	    try {
		final int f = g;
		players[g] = threads[g].call(new Callable <Player> () {

			public Player call() throws Exception
			{
			    Player p = (Player) player_classes[f].newInstance();
			    return p;
			}
		    }, init_timeout);
	    } catch (Exception e) {
		if (players[g] == null)
		    System.err.println("Exception by group " + groups[g] + " constructor");
		else
		    System.err.println("Exception by group " + groups[g] + " init()");
		e.printStackTrace();
		if (exit_on_exception) {
		    System.err.println("Exit on exception ...");
		    System.exit(1);
		}
		players[g] = null;
	    }
	}
	// check if there are any valid players
	for (int g = 0 ; g < p ; g++)
	    if (players[g] != null) return true;
	return false;
    }

    // next state of game
    private static void next()
    {
	turns_without_reproduction++;
	// age all deposited pheromes first
	Iterator<Pherome> it = pheromes.iterator();
	while (it.hasNext()) {
	    if (it.next().step())
		it.remove();
	}
	
	final int numCells = cells.size();
	Collections.shuffle(cells, random);
	for (int i=0; i<numCells; i++) { // only loop through first numCells cells, ignoring cells that are added at end of array through reproduction this turn
	    final Cell active_cell = cells.get(i);
	    final int active_player = active_cell.player;
	    if (players[active_player] != null) {

		// within vision radius of player_cell, supplied to player
		Set<Cell> nearby_cells = new HashSet<Cell>();
		Set<Pherome> nearby_pheromes = new HashSet<Pherome>();
		for (int j=0; j<cells.size(); j++) {
		    if (j == i)
			continue;
		    if (active_cell.distance(cells.get(j)) < d)
			nearby_cells.add(cells.get(j));
		}
		Iterator<Pherome> pherome_it = pheromes.iterator();
		while (pherome_it.hasNext()) {
		    Pherome next = pherome_it.next();
		    if (active_cell.distance(next) < d)
			nearby_pheromes.add(next);
		}

		final Set<Cell> f_nearby_cells = new HashSet<Cell>(nearby_cells);
		final Set<Pherome> f_nearby_pheromes = new HashSet<Pherome>(nearby_pheromes);
		final byte cell_memory = active_cell.memory;

		// get move from player
		Move move = null;
		try {
		    move = threads[active_player].call(new Callable <Move> () {

			    public Move call() throws Exception
			    {
				return players[active_player].play(active_cell, cell_memory, f_nearby_cells, f_nearby_pheromes);
			    }
			}, play_timeout);
		} catch (Exception e) {
		    System.err.println("Exception by " + groups[active_player] + " play()");
		    e.printStackTrace();
		    if (exit_on_exception) {
			System.err.println("Exit on exception ...");
			System.exit(1);
		    }
		    if (e instanceof TimeoutException) {
			System.err.println("Player " + groups[active_player] + " is now invalidated");
			players[active_player] = null;
		    }
		}
		if (move != null) {
		    if (move.reproduce && active_cell.getDiameter() >= 2) { // split into cells of half diameter and send them new_diameter/2 distance at random diametrically opposite directions
			cells.get(i).halve();
			double new_diameter = cells.get(i).getDiameter();
			Cell new_cell = new Cell(cells.get(i).getPosition(), active_player, new_diameter);
			double theta = Math.toRadians((double) random.nextInt(360));
			double dx = new_diameter * 0.5 * Math.cos(theta);
			double dy = new_diameter * 0.5 * Math.sin(theta);
			cells.get(i).move(new Point(dx, dy), nearby_pheromes, nearby_cells);
			new_cell.move(new Point(-1*dx, -1*dy), nearby_pheromes, nearby_cells);
			cells.get(i).memory = move.memory;
			new_cell.memory = move.daughter_memory;
			cells.add(new_cell);
			score[active_player]++;
			System.err.println("Group " + groups[active_player] + " reproduced!");
			turns_without_reproduction = 0;
		    }
		    else { // move the cell, updated memory, grow cell, and secrete pheromes
			cells.get(i).move(move.vector, nearby_pheromes, nearby_cells);
			cells.get(i).memory = move.memory;
			cells.get(i).step(nearby_pheromes, nearby_cells);			
			pheromes.add(cells.get(i).secrete(t));
		    }
		}
	    }	    


	}
    }

    // play game
    private static long play(boolean gui) throws Exception, IOException
    {
	HTTPServer server = null;
	if (gui) {
	    server = new HTTPServer();
	    System.err.println("HTTP port: " + server.port());
	    // try to open web browser automatically
	    if (!Desktop.isDesktopSupported())
		System.err.println("Desktop operations not supported");
	    else if (!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
		System.err.println("Desktop browsing not supported");
	    else {
		URI uri = new URI("http://localhost:" + server.port());
		Desktop.getDesktop().browse(uri);
	    }
	}
	long last_catch = 0;
	long turn = 0;
	for (;;) {
	    // run next turn
	    println("### beg of turn " + turn + " ###");
	    next();
	    if (turns_without_reproduction >= 100)
		break;
	    if (gui) {
		// create dynamic content
		String content =  params() + "\n" + groups_state() + "\n" + cells_state() + "\n" + pheromes_state();
		gui(server, content);
	    }
	    turn++;
	}
	if (server != null) server.close();
	return turn;
    }

    // the main function
    public static void main(String[] args)
    {
	boolean gui = false;
	boolean recompile = false;
	int game_id = -1;
	String game_path = null;
	String play_path = null;
	try {
	    for (int a = 0 ; a != args.length ; ++a)
		/*		if (args[a].equals("-p") || args[a].equals("--players")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing number of players");
		    p = Integer.parseInt(args[a]);
		    if (n_pipers < 1)
		    throw new IllegalArgumentException("Invalid number of players (need at least 1)");
		    } else */
		if (args[a].equals("-n") || args[a].equals("--cells")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing number of cells per player");
		    n = Integer.parseInt(args[a]);
		    if (n < 1)
			throw new IllegalArgumentException("Invalid number of cells (need at least 1)");
		} else if (args[a].equals("-d") || args[a].equals("--distance")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing vision distance");
		    d = Integer.parseInt(args[a]);		    
		} else if (args[a].equals("-t") || args[a].equals("--turns")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing pherome duration");
		    t = Integer.parseInt(args[a]);
		    if (t < 0)
			throw new IllegalArgumentException("Invalid turn (must be positive))");
		} else if (args[a].equals("--fps")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing FPS");
		    double fps = Double.parseDouble(args[a]);
		    if (fps < 0.0)
			throw new IllegalArgumentException("Invalid FPS (must be non-negative)");
		    refresh = fps == 0.0 ? -1 : (int) Math.round(1000.0 / fps);
		} else if (args[a].equals("-g") || args[a].equals("--groups")) {
		    p = args.length - a - 1;
		    groups = new String[p];
		    for (int i=0; i<p; i++) 
			groups[i] = args[++a];
		} else if (args[a].equals("--init-timeout")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing init() timeout");
		    init_timeout = Long.parseLong(args[a]);
		    if (init_timeout < 0) init_timeout = 0;
		} else if (args[a].equals("--play-timeout")) {
		    if (++a == args.length)
			throw new IllegalArgumentException("Missing play() timeout");
		    play_timeout = Long.parseLong(args[a]);
		    if (play_timeout < 0) play_timeout = 0;
		} else if (args[a].equals("--id")) {
		    if (a + 3 >= args.length)
			throw new IllegalArgumentException("Missing id and files");
		    game_id = Integer.parseInt(args[++a]);
		    if (game_id < 0)
			throw new IllegalArgumentException("Invalid game id");
		    game_path = args[++a];
		    play_path = args[++a];
		} else if (args[a].equals("-v") || args[a].equals("--verbose"))
		    verbose = true;
		else if (args[a].equals("--gui"))
		    gui = true;
		else if (args[a].equals("--recompile"))
		    recompile = true;
		else throw new IllegalArgumentException("Unknown argument: " + args[a]);
	    if (groups == null)
		throw new IllegalArgumentException("Missing group name parameter");
	    players = new Player[p];       
	    player_classes = new Class[p];
	    score = new int[p];
	    threads = new Timer[p];
	    
	    load(recompile);
	} catch (Exception e) {
	    System.err.println("Error during setup: " + e.getMessage());
	    e.printStackTrace();
	    System.exit(1);
	}
	if (game_id >= 0) {
	    System.err.close();
	    System.out.close();
	}
	// print parameters
	System.err.println("Players: " + p);
	System.err.println("Groups: " + Arrays.toString(groups));
	System.err.println("Cells: " + n);
	System.err.println("Pherome duration: " + t);
	System.err.println("Verbose: " + (verbose   ? "yes" : "no"));
	System.err.println("Recompile: " + (recompile ? "yes" : "no"));
	if (!gui)
	    System.err.println("GUI: disabled");
	else if (refresh < 0)
	    System.err.println("GUI: enabled  (0 FPS)  [reload manually]");
	else if (refresh == 0)
	    System.err.println("GUI: enabled  (max FPS)");
	else {
	    double fps = 1000.0 / refresh;
	    System.err.println("GUI: enabled  (up to " + fps + " FPS)");
	}
	// initialize and play
	if (!init()) {
	    System.err.println("No valid players to play game");
	    System.exit(1);
	}
	long turns = -1;
	try {
	    turns = play(gui);
	} catch (Exception e) {
	    System.err.println("Error during play: " + e.getMessage());
	    e.printStackTrace();
	    System.exit(1);
	}
	// print scores
	System.err.println("Turns played: " + turns);
	for (int g=0; g<p; g++) {
	    System.err.println("Group " + groups[g] + " scored: " + score[g] + (players[g] != null ? "" : " (disqualified)"));
	}
	System.exit(0);
    }

    private static String params() {
	return Integer.toString(side_length) + "," + Integer.toString(refresh) + "," + Integer.toString(cells.size()) + "," + Integer.toString(pheromes.size());
    }

    private static String groups_state() {	
	StringBuffer buf = new StringBuffer();
	for (int g=0; g<p; g++) {
	    if (g > 1)
		buf.append(";");
	    buf.append(groups[g] + "," + score[g]);
	}
	return buf.toString();
    }


    private static String cells_state()    {
	StringBuffer buf = new StringBuffer();
	Iterator<Cell> it = cells.iterator();
	boolean first = true;
	while (it.hasNext()) {
	    Cell next = it.next();
	    if (first)
		first = false;
	    else
		buf.append(";");
	    buf.append(next.player + "," + next.getPosition().x + "," + next.getPosition().y + "," + next.getDiameter());
	}	    
	return buf.toString();
    }

    private static String pheromes_state() {
	StringBuffer buf = new StringBuffer();
	Iterator<Pherome> it = pheromes.iterator();
	boolean first = true;
	while (it.hasNext()) {
	    Pherome next = it.next();
	    if (first)
		first = false;
	    else
		buf.append(";");
	    buf.append(next.player + "," + next.position.x + "," + next.position.y);
	}
	return buf.toString();
    }
    

    // serve static files and return dynamic file version
    private static void gui(HTTPServer server, String content)
	throws UnknownServiceException
    {
	String path = null;
	for (;;) {
	    // get request
	    for (;;)
		try {
		    path = server.request();
		    break;
		} catch (IOException e) {
		    System.err.println("HTTP request error: " + e.getMessage());
		}
	    println("HTTP request: \"" + path + "\"");
	    // dynamic content
	    if (path.equals("data.txt"))
		// send dynamic content
		try {
		    server.reply(content);
		    println("HTTP dynamic reply: " + content.length() + " bytes");
		    return;
		} catch (IOException e) {
		    System.err.println("HTTP dynamic reply error: " + e.getMessage());
		    continue;
		}
	    // static content
	    if (path.equals("")) path = "webpage.html";
	    else if (!path.equals("favicon.ico") &&
		     !path.equals("apple-touch-icon.png") &&
		     !path.equals("script.js")) break;
	    // send file
	    File file = new File(root + File.separator + "sim"
				 + File.separator + path);
	    try {
		server.reply(file);
		println("HTTP static reply: " + file.length() + " bytes");
	    } catch (IOException e) {
		System.err.println("HTTP static reply error: " + e.getMessage());
	    }
	}
	if (path == null)
	    throw new UnknownServiceException("Unknown HTTP request (null path)");
	else
	    throw new UnknownServiceException("Unknown HTTP request: \"" + path + "\"");
    }

    // print after checking verbose parameter
    private static void print(String msg)
    {
	if (verbose) System.out.print(msg);
    }

    // print after checking verbose parameter
    private static void println(String msg)
    {
	if (verbose) System.out.println(msg);
    }

    // recursive directory scan for files with given extension
    private static Set <File> directory(String path, String extension)
    {
	Set <File> files = new HashSet <File> ();
	Set <File> prev_dirs = new HashSet <File> ();
	prev_dirs.add(new File(path));
	do {
	    Set <File> next_dirs = new HashSet <File> ();
	    for (File dir : prev_dirs)
		for (File file : dir.listFiles())
		    if (!file.canRead()) ;
		    else if (file.isDirectory())
			next_dirs.add(file);
		    else if (file.getPath().endsWith(extension))
			files.add(file);
	    prev_dirs = next_dirs;
	} while (!prev_dirs.isEmpty());
	return files;
    }

    // compile and load source files
    private static void load(boolean compile) throws IOException,
						     ReflectiveOperationException
    {
	// get unique player sources
	Map <String, Class> group_map = new HashMap <String, Class> ();
	for (int g = 0 ; g != groups.length ; ++g)
	    group_map.put(groups[g], null);
	// compile and load classes
	ClassLoader loader = Simulator.class.getClassLoader();
	JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
	StandardJavaFileManager manager = compiler.
	    getStandardFileManager(null, null, null);
	String sep = File.separator;
	for (String group : group_map.keySet()) {
	    String dir = root + sep + group;
	    File class_file = new File(dir + sep + "Player.class");
	    if (compile || !class_file.exists()) {
		File source_file = new File(dir + sep + "Player.java");
		if (!source_file.exists())
		    throw new FileNotFoundException(
						    "Missing source of group " + group);
		Set <File> files = directory(dir, ".java");
		System.err.print("Compiling " + group +
				 " (" + files.size() + " files) ... ");
		if (!compiler.getTask(null, manager, null, null, null,
				      manager.getJavaFileObjectsFromFiles(files)).call())
		    throw new IOException(
					  "Cannot compile source of " + group);
		System.err.println("done!");
		class_file = new File(dir + sep + "Player.class");
		if (!class_file.exists())
		    throw new FileNotFoundException(
						    "Missing class of group " + group);
	    }
	    Class player = loader.loadClass(root + "." + group + ".Player");
	    group_map.replace(group, player);
	}
	// map to players
	for (int g = 0 ; g != groups.length ; ++g)
	    player_classes[g] = group_map.get(groups[g]);
    }
}