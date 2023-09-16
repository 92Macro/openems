package io.openems.edge.dnp3;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;

import static org.joou.Unsigned.*;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import io.stepfunc.dnp3.*;
import io.stepfunc.dnp3.Runtime;
import io.openems.edge.dnp3.*;


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "io.openems.edge.dnp3", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})

public class Dnp3Impl extends AbstractOpenemsComponent implements Dnp3, OpenemsComponent, EventHandler {

	private Config config = null;
	private final Runtime runtime = new Runtime(new RuntimeConfig()); // Create the Tokio runtime
    private OutstationServer server = null;
    private Thread dnp3Thread = null;
    
	private final Logger log = LoggerFactory.getLogger(Dnp3Impl.class);
    
    //private OutstationExample example;
    
    //private OutstationExample example = new OutstationExample();

	public Dnp3Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Dnp3.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		
		//String[] args = {"tcp"};
		//OutstationExample.main(args);
		
		//System.out.printf("Starting DNP thread");
		
		this.logInfo(this.log, "Starting DNP thread ");
		
		
		
		dnp3Thread = new Thread(this.task);
		dnp3Thread.start();

	
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
        // Stop the DNP3 server thread gracefully
        if (dnp3Thread != null) {
            dnp3Thread.interrupt();
            try {
				dnp3Thread.join();
				System.out.printf("Stopping DNP thread");
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			// TODO: fill channels
			break;
		}
	}

	@Override
	public String debugLog() {
		return null/*"Hello World"*/;
	}
	
	private final Runnable task = () -> {
		String[] args = {"tcp"};
		OutstationExample.main(args);
	};

}