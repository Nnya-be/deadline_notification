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
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.Target;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

public class UpdateTaskScheduleLambda implements RequestHandler<DynamodbEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(UpdateTaskScheduleLambda.class);
    private static final String TARGET_LAMBDA_ARN = System.getenv("TARGET_LAMBDA_ARN");
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 60;
    private static final String ACTIVE_STATUS = "active";

    private final SchedulerClient schedulerClient;

    public UpdateTaskScheduleLambda() {
        this.schedulerClient = SchedulerClient.create();
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        for (DynamodbStreamRecord record : event.getRecords()) {
            if (!"MODIFY".equals(record.getEventName())) {
                logger.debug("Skipping non-MODIFY event: {}", record.getEventName());
                continue;
            }

            try {
                Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();
                String taskId = newImage.get("taskId").getS();
                logger.info("Processing MODIFY event for taskId: {}", taskId);

                String newStatus = newImage.getOrDefault("status", createAttr("unknown")).getS();
                if (!ACTIVE_STATUS.equals(newStatus)) {
                    deleteSchedule(taskId);
                    logger.info("Task status {} for taskId: {}; deleted schedule", newStatus, taskId);
                    continue;
                }

                String newDeadline = getOrNull(newImage.get("deadline"));
                String oldDeadline = getOrNull(oldImage.get("deadline"));
                String newAssigneeId = getOrNull(newImage.get("assigneeId"));
                String oldAssigneeId = getOrNull(oldImage.get("assigneeId"));

                if (newDeadline == null) {
                    logger.warn("Missing deadline for taskId: {}", taskId);
                    deleteSchedule(taskId);
                    continue;
                }

                boolean deadlineChanged = !newDeadline.equals(oldDeadline);
                boolean assigneeChanged = !newAssigneeId.equals(oldAssigneeId);

                if (!deadlineChanged && !assigneeChanged) {
                    logger.debug("No relevant changes for taskId: {}", taskId);
                    continue;
                }

                OffsetDateTime deadline;
                try {
                    deadline = OffsetDateTime.parse(newDeadline, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (DateTimeParseException e) {
                    logger.error("Invalid deadline format for taskId: {}: {}", taskId, newDeadline);
                    deleteSchedule(taskId);
                    continue;
                }

                OffsetDateTime reminderTime = deadline.minusMinutes(REMINDER_OFFSET_MINUTES);
                OffsetDateTime now = OffsetDateTime.now();

                if (reminderTime.isBefore(now)) {
                    logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, taskId);
                    deleteSchedule(taskId);
                    continue;
                }

                deleteSchedule(taskId);
                createSchedule(taskId, reminderTime, newImage);

            } catch (Exception e) {
                logger.error("Error processing MODIFY event for taskId: {}: {}",
                        record.getDynamodb().getKeys().get("taskId").getS(), e.getMessage());
            }
        }
        return null;
    }

    private void deleteSchedule(String taskId) {
        try {
            DeleteScheduleRequest request = DeleteScheduleRequest.builder()
                    .name("TaskReminder_" + taskId)
                    .build();
            schedulerClient.deleteSchedule(request);
            logger.info("Deleted schedule for taskId: {}", taskId);
        } catch (ResourceNotFoundException e) {
            logger.debug("No schedule found to delete for taskId: {}", taskId);
        } catch (Exception e) {
            logger.error("Error deleting schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }

    private void createSchedule(String taskId, OffsetDateTime reminderTime, Map<String, AttributeValue> taskItem) {
        try {
            String scheduleExpression = "at(" + reminderTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ")";
            Map<String, String> inputPayload = new HashMap<>();
            taskItem.forEach((key, value) -> {
                if (value.getS() != null) {
                    inputPayload.put(key, value.getS());
                }
            });

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
            logger.info("Created new schedule for taskId: {} at {}", taskId, reminderTime);
        } catch (Exception e) {
            logger.error("Failed to create schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }

    private static AttributeValue createAttr(String value) {
        AttributeValue attr = new AttributeValue();
        attr.setS(value);
        return attr;
    }

    private static String getOrNull(AttributeValue attr) {
        return attr != null ? attr.getS() : null;
    }
}
