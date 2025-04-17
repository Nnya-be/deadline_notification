package deadline_notification.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.Target;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

public class CreateDeadlineEvent implements RequestHandler<DynamodbEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(CreateDeadlineEvent.class);

    private static final String TARGET_LAMBDA_ARN = "arn:aws:lambda:eu-central-1:597088025512:function:ReminderProcessorLambda";
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 60;

    private final SchedulerClient schedulerClient;

    public CreateDeadlineEvent() {
        this.schedulerClient = SchedulerClient.create();
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        for (DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();
            if ("INSERT".equals(eventName) || "MODIFY".equals(eventName)) {
                try {
                    Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                    if (newImage == null) {
                        logger.warn("No newImage found for {} event", eventName);
                        continue;
                    }

                    String taskId = getSafeString(newImage, "taskId");
                    if (taskId == null) {
                        logger.warn("taskId missing in event {}", eventName);
                        continue;
                    }
                    logger.info("Processing {} event for taskId: {}", eventName, taskId);

                    String deadlineStr = getSafeString(newImage, "deadline");
                    if (deadlineStr == null) {
                        logger.warn("No deadline found for taskId: {}", taskId);
                        continue;
                    }

                    OffsetDateTime deadline;
                    try {
                        deadline = OffsetDateTime.parse(deadlineStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    } catch (DateTimeParseException e) {
                        logger.error("Invalid deadline format for taskId: {}: {}", taskId, deadlineStr);
                        continue;
                    }

                    OffsetDateTime reminderTime = deadline.minusMinutes(REMINDER_OFFSET_MINUTES);
                    OffsetDateTime now = OffsetDateTime.now();

                    if (reminderTime.isBefore(now)) {
                        logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, taskId);
                        continue;
                    }

                    createSchedule(taskId, reminderTime, newImage);
                    logger.debug("Record details: {}", newImage);

                } catch (Exception e) {
                    logger.error("Error processing record for event {}: {}", eventName, e.getMessage(), e);
                }
            } else {
                logger.debug("Skipping event: {}", eventName);
            }
        }
        return null;
    }

    private void createSchedule(String taskId, OffsetDateTime reminderTime, Map<String, AttributeValue> taskItem) {
        try {
            String scheduleExpression = "at(" + reminderTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("Z", "") + ")";

            Map<String, String> inputPayload = new HashMap<>();
            inputPayload.put("taskId", taskId);

            taskItem.forEach((key, value) -> {
                if (value.getS() != null) {
                    inputPayload.put(key, value.getS());
                }
            });

            logger.info("Creating EventBridge schedule for the role ARN: {}", SCHEDULER_ROLE_ARN);
            logger.info("Creating EventBridge schedule for the target ARN: {}", TARGET_LAMBDA_ARN);
            CreateScheduleRequest request = CreateScheduleRequest.builder()
                    .name("TaskReminder_" + taskId)
                    .scheduleExpression(scheduleExpression)
                    .state(ScheduleState.ENABLED)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode("OFF").build())
                    .target(Target.builder()
                            .arn(TARGET_LAMBDA_ARN)
                            .roleArn(SCHEDULER_ROLE_ARN)
                            .input(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inputPayload))
                            .build())
                    .build();

            schedulerClient.createSchedule(request);
            logger.info("Created EventBridge schedule for taskId: {} at {}", taskId, reminderTime);

        } catch (Exception e) {
            logger.error("Failed to create schedule for taskId: {}: {}", taskId, e.getMessage(), e);
        }
    }

    /**
     * Utility method to safely extract a String from a DynamoDB image map
     */
    private String getSafeString(Map<String, AttributeValue> map, String key) {
        if (map == null) {
            logger.warn("Map is null for key: {}", key);
            return null;
        }
        AttributeValue val = map.get(key);
        return (val != null && val.getS() != null && !val.getS().isEmpty()) ? val.getS() : null;
    }
}