package com.jjinterna.pbxevents.mediator.activemq.internal;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

import com.jjinterna.pbxevents.model.PBXEvent;
import com.jjinterna.pbxevents.routes.EventSelector;

public class SubscriberRouteBuilder extends RouteBuilder {

	private String destinationName;
	private String destinationType;	
	private List<EventSelector> selectors;
	private String destUri;
	
	public SubscriberRouteBuilder(String destinationName, String destinationType, List<EventSelector> selectors, String destUri) {
		this.destinationName = destinationName;
		this.destinationType = destinationType;
		this.selectors = new ArrayList<EventSelector>(selectors);
		this.destUri = destUri;
	}

	@Override
	public void configure() throws Exception {
		fromF("activemq:%s:%s?username=karaf&password=karaf", destinationType, destinationName)
			.id("subscribe")
			.process(new Processor() {

				@Override
				public void process(Exchange exchange) throws Exception {
					if (exchange.getIn().getBody() instanceof PBXEvent) {
						PBXEvent event = (PBXEvent) exchange.getIn().getBody();
						for (EventSelector selector : selectors) {
							if (!selector.select(event)) {
								exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE);
								break;
							}
						}
					}
				}

			})
			.to(destUri);
	}

}
