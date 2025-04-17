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
        // Validate environment variables
        if (TARGET_LAMBDA_ARN == null || SCHEDULER_ROLE_ARN == null) {
            logger.error("Environment variables TARGET_LAMBDA_ARN or SCHEDULER_ROLE_ARN are not set");
            throw new IllegalStateException("Required environment variables are not set");
        }

        for (DynamodbStreamRecord record : event.getRecords()) {
            if (!"MODIFY".equals(record.getEventName())) {
                logger.debug("Skipping non-MODIFY event: {}", record.getEventName());
                continue;
            }

            try {
                Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();
                String taskId = getOrNull(newImage.get("taskId"));
                if (taskId == null) {
                    logger.error("taskId is missing for event: {}", record);
                    continue;
                }
                logger.info("Processing MODIFY event for taskId: {}", taskId);

                String newStatus = getOrNull(newImage.get("status"));
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

                OffsetDateTime deadline = parseDeadline(newDeadline, taskId);
                if (deadline == null) continue;

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

    private static String getOrNull(AttributeValue attr) {
        return attr != null ? attr.getS() : null;
    }

    private static OffsetDateTime parseDeadline(String deadline, String taskId) {
        try {
            return OffsetDateTime.parse(deadline, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            logger.error("Invalid deadline format for taskId: {}: {}", taskId, deadline);
            return null;
        }
    }
}
