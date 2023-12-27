package io.openems.edge.genset.deepsea8660.controller;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Genset loadoffset Controller", //
		description = "")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "genset.controller0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;
	
	@AttributeDefinition(name = "GenSize-ID", description = "Total Generator kW capacity.")
	int gen_size_id() default 1000;
	
	@AttributeDefinition(name = "GenPercentage-ID", description = "Percentage (%) of load that is to be supplied by Genset.")
	int gen_percentage_id() default 30;	
	
	@AttributeDefinition(name = "Loadshedding-ID", description = "ID of the Loadshedding API.")
	String loadshedding_id();
	
	@AttributeDefinition(name = "SynPanel-ID", description = "ID of the Genset Sync Panel.")
	String syncpanel_id();	

	@AttributeDefinition(name = "Genset1-ID", description = "ID of the Genset1 Device.")
	String genset1_id();	
	
	@AttributeDefinition(name = "Genset2-ID", description = "ID of the Genset2 Device.")
	String genset2_id();
	
	@AttributeDefinition(name = "Genset3-ID", description = "ID of the Genset3 Device.")
	String genset3_id();	
	
	String webconsole_configurationFactory_nameHint() default "Genset loadoffset Controller[{id}]";

}