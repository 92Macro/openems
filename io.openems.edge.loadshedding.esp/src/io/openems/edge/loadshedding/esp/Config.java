package io.openems.edge.loadshedding.esp;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Loadshedding Esp", //
		description = "Loadshedding Eskom-se-push implementation")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "loadshedding0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;
	
	@AttributeDefinition(name = "Eskom-se-push Id", description = "Unique eskom-se-push api id")
	String esp_id() default "";	
	
	@AttributeDefinition(name = "Key", description = "Unique eskom-se-push api key")
	String key() default "";
	
	@AttributeDefinition(name = "Is TestMode enabled?", description = "Tick to simulate active loadshedding")
	boolean test() default false;	
	
	@AttributeDefinition(name = "Loadshed start time", description = "Input start time of Simulated Loadshedding")
	long start_time() default 0;	
	
	String webconsole_configurationFactory_nameHint() default "Loadshedding Esp [{id}]";
}