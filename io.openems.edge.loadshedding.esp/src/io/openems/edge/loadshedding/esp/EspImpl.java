package io.openems.edge.loadshedding.esp;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;

import io.openems.edge.loadshedding.api.Loadshedding;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Loadshedding.Esp", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
/*@EventTopics({ //
	EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})*/
public class EspImpl extends AbstractOpenemsComponent implements Esp, OpenemsComponent, Loadshedding {

	private Config config = null;
	private static final String ESP_API_URL = "https://developer.sepush.co.za/business/2.0/";
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private String apiResponse = null;

	private final Runnable task = () -> {

		/*
		 * Build API request
		 */	
		OkHttpClient client = new OkHttpClient().newBuilder().build();
				  
		Request request = new Request.Builder() //
			// ESP uses "Token" to send the Apikey.
			//.url("https://developer.sepush.co.za/business/2.0/area?id=" + this.config.esp_id().toString())
			.url(ESP_API_URL + "area?id=" + this.config.esp_id().toString())
			.header("Token", this.config.key().toString())
			.build();
	
		int httpStatusCode = 0;
		int stageNumber = 0;
        Instant epochInstant = Instant.ofEpochSecond(0);
        
        ZoneId zoneId = ZoneId.of("UTC+2");
        ZonedDateTime startDateTime = ZonedDateTime.ofInstant(epochInstant, zoneId);
        ZonedDateTime endDateTime  = ZonedDateTime.ofInstant(epochInstant, zoneId);
		
        if (this.config.test()) {
        	httpStatusCode = 0;
        	stageNumber = 1;
        	
        	Instant epochTestStart = Instant.ofEpochSecond(this.config.start_time());
            startDateTime = ZonedDateTime.ofInstant(epochTestStart, zoneId);
            Instant epochTestEnd = Instant.ofEpochSecond(this.config.start_time() + this.config.total_time());
            endDateTime  = ZonedDateTime.ofInstant(epochTestEnd, zoneId);
        }	//make use of override features..
        else {
			try (Response response = client.newCall(request).execute()) {
				httpStatusCode = response.code();
	
				if (!response.isSuccessful()) {
					throw new IOException("Unexpected code " + response);
				}
	
				this.apiResponse = response.body().string();
		
				Gson gson = new Gson();
				JsonObject jsonObject = gson.fromJson(this.apiResponse, JsonObject.class);
				JsonArray eventsArray = jsonObject.getAsJsonArray("events");
	
				if (eventsArray != null && eventsArray.size() > 0) {
				    JsonObject eventObject = eventsArray.get(0).getAsJsonObject();
				    String start = eventObject.get("start").getAsString();
				    String end = eventObject.get("end").getAsString();
				    String note = eventObject.get("note").getAsString();
				
		            startDateTime = ZonedDateTime.parse(start, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		            endDateTime = ZonedDateTime.parse(end, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	
		            // Extract the stage number from the note field
		            stageNumber = extractStageNumber(note);	
				}
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
	    }	//Try a normal api call

		this.channel(Esp.ChannelId.HTTP_STATUS_CODE).setNextValue(httpStatusCode);
		this.channel(Loadshedding.ChannelId.TIME_OF_SHED_START).setNextValue(startDateTime.toEpochSecond());	
		this.channel(Loadshedding.ChannelId.TIME_OF_SHED_END).setNextValue(endDateTime.toEpochSecond());
		this.channel(Loadshedding.ChannelId.STAGE).setNextValue(stageNumber);
	
		/*
		 * Schedule next update for 30 mins from now
		 */
		
		var delay = 1800; //30 min 1800  
		this.executor.schedule(this.task, delay, TimeUnit.SECONDS);
	};

	@Reference
	private ComponentManager componentManager;

	public EspImpl() {
		super(//
			OpenemsComponent.ChannelId.values(), //
			Esp.ChannelId.values(), //
			Loadshedding.ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		
		if (!config.enabled()) {
			return;
		}

		this.executor.schedule(this.task, 0, TimeUnit.SECONDS);
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

/*	@Override
	public String debugLog() {
		var tempResponse = this.apiResponse;
		//this.apiResponse = null;
		return "Esp api response: " + tempResponse;
	}*/
	
/*	@Override
	public void handleEvent(Event event) {
		super.handleEvent(event);
	}*/
	
	private static int extractStageNumber(String note) {
		Pattern pattern = Pattern.compile("stage\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(note);
		
		if (matcher.find()) {
		    String stageNumberStr = matcher.group(1);
		    return Integer.parseInt(stageNumberStr);
		}
		
		return 0; // Return 0 if no stage number found
	}
}
