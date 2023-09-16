package io.openems.edge.loadshedding.esp;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.loadshedding.api.Loadshedding;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.ZonedDateTime;
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
public class EspImpl extends AbstractOpenemsComponent implements Esp, OpenemsComponent, Loadshedding/*, EventHandler*/ {

	private Config config = null;
	
	private static final String ESP_API_URL = "https://developer.sepush.co.za/business/2.0/";

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();


	private ZonedDateTime updateTimeStamp = null;
	
	private String apiResponse = null;

	private final Runnable task = () -> {

		/*
		 * Update Map of prices
		 */	

		OkHttpClient client = new OkHttpClient().newBuilder()
				  .build();

		Request request = new Request.Builder() //
				//.url(ESP_API_URL + "area?id=" + ) //
				// aWATTar currently does not anymore require an Apikey.
				 .url("https://developer.sepush.co.za/business/2.0/area?id=" + this.config.esp_id().toString())
				.header("Token", this.config.key().toString())

				.build();
	
		int httpStatusCode;
		int stageNumber = 0;
        ZonedDateTime startDateTime = null;
        ZonedDateTime endDateTime  = null;
		
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
	            
	            /*System.out.println("Stage Number: " + stageNumber);
				System.out.println("Start: " + start);
				System.out.println("End: " + end);*/
				
					
			}
	
		} catch (IOException /*|  OpenemsNamedException | ParseException*/ e) {
			e.printStackTrace();
			httpStatusCode = 0;
			// TODO Try again in x minutes
		}

		this.channel(Esp.ChannelId.HTTP_STATUS_CODE).setNextValue(httpStatusCode);
		this.channel(Loadshedding.ChannelId.TIMEOFSHEDSTART).setNextValue(startDateTime.toEpochSecond());	
		this.channel(Loadshedding.ChannelId.TIMEOFSHEDEND).setNextValue(endDateTime.toEpochSecond());
		this.channel(Loadshedding.ChannelId.STAGE).setNextValue(stageNumber);
	
		
		/*
		 * Schedule next update for next hour change
		 */
		/*var now = ZonedDateTime.now();
		var nextRun = now.withHour(14).truncatedTo(ChronoUnit.HOURS);
		if (now.isAfter(nextRun)) {
			nextRun = nextRun.plusDays(1);
		}

		var duration = Duration.between(now, nextRun);
		var delay = duration.getSeconds();*/
		var delay = 1800; //30 min 1800  
		
		this.executor.schedule(this.task, delay, TimeUnit.SECONDS);
	};
	
	//////////////////////////

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

	        return -1; // Return -1 if no stage number found
	    }
}
