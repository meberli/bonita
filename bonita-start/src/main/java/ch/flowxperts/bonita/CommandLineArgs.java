package ch.flowxperts.bonita;

import java.util.ArrayList;
import java.util.List;

import com.beust.jcommander.Parameter;


public class CommandLineArgs {
	
	@Parameter
	public List<String> parameters = new ArrayList<String>();

	@Parameter(names = { "-log", "-verbose" }, description = "Level of verbosity")
	public Integer verbose = 1;
	 
	@Parameter(names = "-filename", description = "Full Path to the file to import")
	public String filename;

	@Parameter(names = "-stackId", description = "StackID of SmartFix Stack to import")
	public String stackId;  

}
