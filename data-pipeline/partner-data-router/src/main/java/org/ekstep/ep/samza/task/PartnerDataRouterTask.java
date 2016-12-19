package org.ekstep.ep.samza.task;

import org.apache.samza.config.Config;
import org.apache.samza.metrics.Counter;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.*;
import org.ekstep.ep.samza.Event;
import org.ekstep.ep.samza.cleaner.CleanerFactory;
import org.ekstep.ep.samza.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

public class PartnerDataRouterTask implements StreamTask, InitableTask, WindowableTask {
    private static final List<String> validPartners =
            asList("org.ekstep.partner.akshara", "org.ekstep.partner.pratham", "org.ekstep.partner.enlearn", "9e94fb35");
    private String successTopicSuffix;
    private Counter messageCount;
    private CleanerFactory cleaner;
    private List<String> eventsToAllow;
    private List<String> eventsToSkip;

    static Logger LOGGER = new Logger(Event.class);

    @Override
    public void init(Config config, TaskContext context) throws Exception {
        successTopicSuffix = config.get("output.success.topic.prefix", "partner");
        messageCount = context
                .getMetricsRegistry()
                .newCounter(getClass().getName(), "message-count");
        eventsToSkip = getEventsToSkip(config);
        eventsToAllow = getEventsToAllow(config);
        cleaner = new CleanerFactory(eventsToAllow, eventsToSkip);
    }

    @Override
    public void process(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator) throws Exception {
        Map<String, Object> message = (Map<String, Object>) envelope.getMessage();
        Event event = getEvent(message);
        processEvent(collector, event);
        messageCount.inc();
    }

    public void processEvent(MessageCollector collector, Event event) {
        LOGGER.info(event.id(), "TS: {}", event.ts());
        LOGGER.info(event.id(), "SID: {}", event.sid());
        if (!event.belongsToAPartner()) {
            return;
        }
        event.updateType();
        String topic = String.format("%s.%s", successTopicSuffix, event.routeTo());
        LOGGER.info(event.id(), "TOPIC: {}", topic);

        if (event.getData().containsKey("ver") && event.getData().get("ver").equals("1.0")) {
            return;
        }

        if (cleaner.shouldAllowEvent(event.eid())) {
            if (cleaner.shouldSkipEvent(event.eid())) {
                return;
            }

            cleaner.clean(event.getData());
            LOGGER.info(event.id(), "CLEANED EVENT");

            SystemStream stream = new SystemStream("kafka", topic);
            collector.send(new OutgoingMessageEnvelope(stream, event.getData()));
        }
    }

    private List<String> getEventsToSkip(Config config) {
        String[] split = config.get("events.to.skip", "").split(",");
        List<String> eventsToSkip = new ArrayList<String>();
        for (String event : split) {
            eventsToSkip.add(event.trim().toUpperCase());
        }
        return eventsToSkip;
    }

    private List<String> getEventsToAllow(Config config) {
        String[] split = config.get("events.to.allow", "").split(",");
        List<String> eventsToAllow = new ArrayList<String>();
        for (String event : split) {
            eventsToAllow.add(event.trim().toUpperCase());
        }
        return eventsToAllow;
    }

    protected Event getEvent(Map<String, Object> message) {
        return new Event(message, validPartners);
    }

    @Override
    public void window(MessageCollector collector, TaskCoordinator coordinator) throws Exception {
        messageCount.clear();

    }
}
