package com.jjinterna.pbxevents.asterisk.queue.internal;

import java.net.URLEncoder;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.CsvDataFormat;
import org.apache.camel.processor.aggregate.AggregationStrategy;

import com.jjinterna.pbxevents.model.CallConnect;
import com.jjinterna.pbxevents.model.CallEnterQueue;
import com.jjinterna.pbxevents.routes.logfile.LogfileLifecycleStrategySupport;
import com.jjinterna.pbxevents.routes.logfile.LogfileMark;

public class AstQueueLogRoute extends RouteBuilder {

    // Configured fields
	private String camelRouteId;
    private String fileName;

	@Override
	public void configure() throws Exception {
		AggregationStrategy callEventAggregationStrategy = new CallEventAggregationStrategy();
		final LogfileMark queueLogMark = new LogfileMark("data/mark_" + URLEncoder.encode(fileName, "UTF-8"));
		final int mark = queueLogMark.getMark();
		
		from("direct:callComplete")
		.id(camelRouteId + ".callComplete")
		.startupOrder(100)
		.aggregate(header("PBXCallID"), callEventAggregationStrategy)
			.completionTimeout(3600 * 1000)
			.eagerCheckCompletion()
			.completionPredicate(new Predicate() {
				@Override
				public boolean matches(Exchange exchange) {
					if (exchange.getIn().getBody() instanceof QueueLog) {
						QueueLog queueLog = (QueueLog) exchange.getIn().getBody();
						return queueLog.getVerb().equals(QueueLogType.COMPLETEAGENT) ||
								queueLog.getVerb().equals(QueueLogType.COMPLETECALLER);
					}
					return false;
				}
			})
		.to("direct:publish");
		
		from("direct:callConnect")
		.id(camelRouteId + ".callConnect")
		.startupOrder(101)
		.aggregate(header("PBXCallID"), callEventAggregationStrategy)
			.completionTimeout(3600 * 1000)	
			.eagerCheckCompletion()
			.completionPredicate(new Predicate() {				
				@Override
				public boolean matches(Exchange exchange) {
					if (exchange.getIn().getBody() instanceof QueueLog) {
						QueueLog queueLog = (QueueLog) exchange.getIn().getBody();
						return queueLog.getVerb().equals(QueueLogType.ABANDON) ||
								queueLog.getVerb().equals(QueueLogType.EXITWITHTIMEOUT) ||
								queueLog.getVerb().equals(QueueLogType.CONNECT);
					}
					return false;
				}
			})
		.multicast()
			.to("direct:publish")
			.filter(header("PBXEvent").isEqualTo(CallConnect.class.getSimpleName())).to("direct:callComplete").end()
		.end();

		from("direct:callEnterQueue")
		.id(camelRouteId + ".callEnterQueue")
		.startupOrder(102)
		.aggregate(header("PBXCallID"), callEventAggregationStrategy)
			.completionTimeout(60 * 1000)
			.completionPredicate(header("PBXEvent").isEqualTo(CallEnterQueue.class.getSimpleName()))
		.multicast()
			.to("direct:publish", "direct:callConnect");

		from("direct:queueLog")
		.id(camelRouteId + ".queueLog")
		.startupOrder(103)
		.choice()
			.when(header("PBXEvent").in(
					QueueLogType.DID,
					QueueLogType.ENTERQUEUE))
				.to("direct:callEnterQueue").stop()
			.when(header("PBXEvent").in(
					QueueLogType.ABANDON,
					QueueLogType.CONNECT,
					QueueLogType.EXITWITHTIMEOUT,
					QueueLogType.RINGNOANSWER))
				.to("direct:callConnect").stop()
			.when(header("PBXEvent").in(
					QueueLogType.COMPLETEAGENT,
					QueueLogType.COMPLETECALLER))
				.to("direct:callComplete").stop()
		.end();

		from("stream:file?fileName={{fileName}}&scanStream=true&scanStreamDelay=1000")
		.id(camelRouteId)
		.unmarshal(new CsvDataFormat("|"))
		.process(new Processor() {
			@Override
			public void process(Exchange exchange) throws Exception {
		        Message in = exchange.getIn();
		        List<List<String>> csvData = (List<List<String>>) in.getBody();
		        List<String> fields = csvData.get(0);
		        for (int i = fields.size(); i<10; i++) {
		        	fields.add("");
		        }
		        
		        QueueLog queueLog = new QueueLog();
		        queueLog.setTimeId(Integer.parseInt(fields.get(0)));
		        queueLog.setCallId(fields.get(1));
		        queueLog.setQueue(fields.get(2));
		        queueLog.setAgent(fields.get(3));
		        try {
		        	queueLog.setVerb(QueueLogType.fromValue(fields.get(4)));
		        }
		        catch (IllegalArgumentException e) {
		        	// unknown verb
		        	exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE);
		        	return;
		        }
		        queueLog.setData1(fields.get(5));
		        queueLog.setData2(fields.get(6));
		        queueLog.setData3(fields.get(7));
		        queueLog.setData4(fields.get(8));
		        queueLog.setData5(fields.get(9));

		        in.setHeader("PBXCallID", queueLog.getCallId());
		        in.setHeader("PBXEvent", queueLog.getVerb().toString());
		        in.setHeader("PBXQueue", queueLog.getQueue());
		        
		        in.setBody(queueLog);

		        if (queueLog.getTimeId() <= mark) {
		        	exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE);
		        } else {
		        	try {
		        		queueLogMark.setMark(fields.get(0));
		        	}
		        	catch (Exception e) {}
		        }
			}
		})
		.to("direct:queueLog");

		getContext().addLifecycleStrategy(new LogfileLifecycleStrategySupport(camelRouteId, fileName));

	}
}
