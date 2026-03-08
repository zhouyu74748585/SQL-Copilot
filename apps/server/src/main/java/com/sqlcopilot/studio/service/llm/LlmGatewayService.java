package com.sqlcopilot.studio.service.llm;

import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.dto.ai.AiModelOptionVO;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 统一 LLM 网关，屏蔽 OpenAI/CLI provider 差异。 */
@Service
public class LlmGatewayService {

    private static final Pattern COMMAND_TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern TOKEN_USAGE_VALUE_PATTERN = Pattern.compile("^[0-9][0-9,]*$");
    private static final Pattern TOKEN_USAGE_KV_PATTERN =
        Pattern.compile("(?i)\\b(input|prompt|output|completion|total)\\b[^0-9]*([0-9][0-9,]*)");
    private static final Pattern TOKEN_USAGE_NUMBER_PATTERN = Pattern.compile("([0-9][0-9,]*)");
    private static final Set<String> CODEX_SUBCOMMANDS = Set.of(
        "exec", "e", "review", "login", "logout", "mcp", "mcp-server",
        "app-server", "app", "completion", "sandbox", "debug", "apply",
        "a", "resume", "fork", "cloud", "features", "help"
    );
    private static final long CLI_TIMEOUT_SECONDS = 45L;

    private final AiConfigService aiConfigService;
    private final OpenAiTextClient openAiTextClient;

    public LlmGatewayService(AiConfigService aiConfigService, OpenAiTextClient openAiTextClient) {
        this.aiConfigService = aiConfigService;
        this.openAiTextClient = openAiTextClient;
    }

    public LlmGatewayResult call(LlmGatewayRequest request) {
        AiConfigVO config = aiConfigService.getConfig();
        AiModelOptionVO option = resolveModelOption(request, config);
        if ("LOCAL_CLI".equalsIgnoreCase(safe(option.getProviderType()))) {
            return callLocalCli(request, option);
        }
        return callOpenAi(request, option);
    }

    private LlmGatewayResult callOpenAi(LlmGatewayRequest request, AiModelOptionVO option) {
        String apiKey = safe(option.getOpenaiApiKey());
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "OpenAI API Key 未配置: " + safe(option.getName()));
        }
        String actualModel = resolveOpenAiModel(request, option);
        String baseUrl = safe(option.getOpenaiBaseUrl());
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        OpenAiTextClient.OpenAiTextResult result = openAiTextClient.requestText(
            apiKey,
            baseUrl,
            actualModel,
            safe(request.getSystemPrompt()),
            safe(request.getUserPrompt()),
            request.getTimeout() == null ? Duration.ofSeconds(30) : request.getTimeout(),
            request.getTemperature() == null ? 0.1D : request.getTemperature()
        );
        String content = safe(result.content());
        if (content.isBlank()) {
            throw new BusinessException(500, "OpenAI 返回内容为空");
        }
        LlmGatewayResult gatewayResult = new LlmGatewayResult();
        gatewayResult.setModelId(safe(option.getId()));
        gatewayResult.setProviderType("OPENAI");
        gatewayResult.setProviderName(safe(option.getName()));
        gatewayResult.setActualModel(actualModel);
        gatewayResult.setSystemPrompt(safe(request.getSystemPrompt()));
        gatewayResult.setUserPrompt(safe(request.getUserPrompt()));
        gatewayResult.setContent(content);
        gatewayResult.setFullOutput(content);
        gatewayResult.setReasoning("已通过 OpenAI API(" + safe(option.getName()) + "/" + actualModel + ")完成" + safe(request.getTaskLabel()));
        gatewayResult.setUsage(result.usage());
        return gatewayResult;
    }

    private LlmGatewayResult callLocalCli(LlmGatewayRequest request, AiModelOptionVO option) {
        String command = safe(option.getCliCommand());
        if (command.isBlank()) {
            throw new BusinessException(400, "本地 CLI 命令未配置: " + safe(option.getName()));
        }
        String constrainedPrompt = buildCliConstrainedPromptForText(request.getSystemPrompt(), request.getUserPrompt());
        List<String> commandLine = parseCliCommand(command);
        if (commandLine.isEmpty()) {
            throw new BusinessException(400, "本地 CLI 命令无效");
        }
        CliInvocation cliInvocation = buildCliInvocation(commandLine, constrainedPrompt);
        ProcessBuilder processBuilder = new ProcessBuilder(cliInvocation.commandLine());
        processBuilder.redirectErrorStream(true);
        String workingDir = safe(option.getCliWorkingDir());
        if (!workingDir.isBlank()) {
            processBuilder.directory(new File(workingDir));
        }
        try {
            Process process = processBuilder.start();
            if (cliInvocation.writePromptToStdin()) {
                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(constrainedPrompt.getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                    stdin.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(500, "本地 CLI 执行超时");
            }
            String rawOutput = new String(outputBytes, StandardCharsets.UTF_8);
            boolean codexCli = isCodexExecutable(cliInvocation.commandLine().get(0));
            String content = extractTextFromCliOutput(rawOutput, codexCli, expectsJsonOutput(request.getSystemPrompt()));
            if (content.isBlank()) {
                throw new BusinessException(500, "本地 CLI 输出为空");
            }
            LlmGatewayResult gatewayResult = new LlmGatewayResult();
            gatewayResult.setModelId(safe(option.getId()));
            gatewayResult.setProviderType("LOCAL_CLI");
            gatewayResult.setProviderName(safe(option.getName()));
            gatewayResult.setActualModel(summarizeCommandHead(cliInvocation.commandLine()));
            gatewayResult.setSystemPrompt(safe(request.getSystemPrompt()));
            gatewayResult.setUserPrompt(safe(request.getUserPrompt()));
            gatewayResult.setContent(content);
            gatewayResult.setFullOutput(content);
            gatewayResult.setReasoning("已通过本地 CLI(" + safe(option.getName()) + ")完成" + safe(request.getTaskLabel()));
            gatewayResult.setUsage(parseCliTokenUsage(rawOutput, constrainedPrompt, content, codexCli));
            return gatewayResult;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "本地 CLI 调用失败: " + safe(ex.getMessage()));
        }
    }

    private AiModelOptionVO resolveModelOption(LlmGatewayRequest request, AiConfigVO config) {
        List<AiModelOptionVO> options = config.getModelOptions() == null ? List.of() : config.getModelOptions();
        String target = safe(request.getModelId());
        if (target.isBlank()) {
            target = safe(request.getLegacyModelName());
        }
        if (!target.isBlank()) {
            for (AiModelOptionVO option : options) {
                if (option != null && target.equalsIgnoreCase(safe(option.getId()))) {
                    return option;
                }
            }
        }
        if (!options.isEmpty()) {
            return options.get(0);
        }
        AiModelOptionVO fallback = new AiModelOptionVO();
        fallback.setId("openai-default");
        fallback.setName("OpenAI gpt-4.1-mini");
        fallback.setProviderType("OPENAI");
        fallback.setOpenaiBaseUrl("https://api.openai.com/v1");
        fallback.setOpenaiModel("gpt-4.1-mini");
        return fallback;
    }

    private String resolveOpenAiModel(LlmGatewayRequest request, AiModelOptionVO option) {
        String requestValue = safe(request.getLegacyModelName());
        if (!requestValue.isBlank() && !requestValue.equalsIgnoreCase(safe(option.getId()))) {
            return requestValue;
        }
        String raw = safe(option.getOpenaiModel());
        if (raw.isBlank()) {
            return "gpt-4.1-mini";
        }
        for (String token : raw.split("[,\\n\\r\\t]")) {
            String model = token.trim();
            if (!model.isBlank()) {
                return model;
            }
        }
        return "gpt-4.1-mini";
    }

    private String buildCliConstrainedPromptForText(String systemPrompt, String basePrompt) {
        return """
            %s
            严格要求:
            1. 仅基于下面给出的用户需求、SQL片段（如有）和 RAG Context 输出结果。
            2. 禁止扫描、读取或参考当前工程、仓库、本地文件系统的任何内容。
            3. 仅输出结果文本，不要输出执行命令。

            %s
            """.formatted(safe(systemPrompt), safe(basePrompt));
    }

    private List<String> parseCliCommand(String rawCommand) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = COMMAND_TOKEN_PATTERN.matcher(safe(rawCommand));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                token = matcher.group(2);
            }
            if (token == null) {
                token = matcher.group(3);
            }
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private CliInvocation buildCliInvocation(List<String> parsedCommand, String prompt) {
        List<String> commandLine = new ArrayList<>(parsedCommand);
        if (isCodexExecutable(commandLine.get(0))) {
            if (!containsCodexSubcommand(commandLine)) {
                commandLine.add("exec");
            }
            commandLine.add(prompt);
            return new CliInvocation(commandLine, false);
        }
        commandLine.add(prompt);
        return new CliInvocation(commandLine, true);
    }

    private boolean isCodexExecutable(String executable) {
        return "codex".equalsIgnoreCase(new File(safe(executable)).getName());
    }

    private boolean containsCodexSubcommand(List<String> commandLine) {
        for (int i = 1; i < commandLine.size(); i++) {
            if (CODEX_SUBCOMMANDS.contains(safe(commandLine.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private String extractTextFromCliOutput(String rawOutput, boolean codexCli, boolean expectJsonOutput) {
        String normalized = normalizeCliRawOutput(rawOutput);
        if (normalized.isBlank()) {
            return "";
        }
        if (!codexCli) {
            return normalized;
        }
        String codexMessage = extractCodexMessage(normalized);
        if (!codexMessage.isBlank()) {
            if (expectJsonOutput) {
                String jsonPayload = extractLastJsonPayload(codexMessage);
                if (!jsonPayload.isBlank()) {
                    return jsonPayload;
                }
            }
            return codexMessage;
        }
        return normalized;
    }

    private String normalizeCliRawOutput(String rawOutput) {
        String output = Objects.toString(rawOutput, "").replace("\r\n", "\n").replace('\r', '\n');
        output = ANSI_ESCAPE_PATTERN.matcher(output).replaceAll("");
        return output.trim();
    }

    private boolean expectsJsonOutput(String systemPrompt) {
        return safe(systemPrompt).toLowerCase(Locale.ROOT).contains("json");
    }

    private String extractCodexMessage(String output) {
        List<String> lines = new ArrayList<>(List.of(safe(output).split("\n", -1)));
        if (lines.isEmpty()) {
            return "";
        }
        int codexLineIndex = lastLineIndexIgnoreCase(lines, "codex");
        if (codexLineIndex >= 0 && codexLineIndex + 1 < lines.size()) {
            lines = new ArrayList<>(lines.subList(codexLineIndex + 1, lines.size()));
        }
        int tokensLineIndex = lastLineIndexIgnoreCase(lines, "tokens used");
        if (tokensLineIndex >= 0) {
            String contentAfterTokens = extractContentAfterTokenUsage(lines, tokensLineIndex);
            if (!contentAfterTokens.isBlank()) {
                return contentAfterTokens;
            }
            String contentBeforeTokens = String.join("\n", lines.subList(0, tokensLineIndex)).trim();
            if (!contentBeforeTokens.isBlank()) {
                return contentBeforeTokens;
            }
        }
        return String.join("\n", lines).trim();
    }

    private int lastLineIndexIgnoreCase(List<String> lines, String expected) {
        String target = safe(expected);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (target.equalsIgnoreCase(safe(lines.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private String extractContentAfterTokenUsage(List<String> lines, int tokensLineIndex) {
        int index = tokensLineIndex + 1;
        while (index < lines.size() && safe(lines.get(index)).isBlank()) {
            index++;
        }
        while (index < lines.size() && TOKEN_USAGE_VALUE_PATTERN.matcher(safe(lines.get(index))).matches()) {
            index++;
        }
        while (index < lines.size() && safe(lines.get(index)).isBlank()) {
            index++;
        }
        if (index >= lines.size()) {
            return "";
        }
        return String.join("\n", lines.subList(index, lines.size())).trim();
    }

    private OpenAiTextClient.TokenUsage parseCliTokenUsage(String rawOutput,
                                                           String promptText,
                                                           String completionText,
                                                           boolean codexCli) {
        if (!codexCli) {
            return null;
        }
        String normalized = normalizeCliRawOutput(rawOutput);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        int tokensLineIndex = lastLineIndexContainsIgnoreCase(lines, "tokens used");
        if (tokensLineIndex < 0) {
            return null;
        }
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        List<Integer> orderedNumbers = new ArrayList<>();
        boolean sawUsageLine = false;
        for (int i = tokensLineIndex + 1; i < lines.size(); i++) {
            String line = safe(lines.get(i));
            if (line.isBlank()) {
                if (sawUsageLine) {
                    break;
                }
                continue;
            }
            Matcher kvMatcher = TOKEN_USAGE_KV_PATTERN.matcher(line);
            if (kvMatcher.find()) {
                sawUsageLine = true;
                String key = safe(kvMatcher.group(1)).toLowerCase(Locale.ROOT);
                int value = parseTokenNumber(kvMatcher.group(2));
                if (value <= 0) {
                    continue;
                }
                if ("input".equals(key) || "prompt".equals(key)) {
                    promptTokens = value;
                } else if ("output".equals(key) || "completion".equals(key)) {
                    completionTokens = value;
                } else if ("total".equals(key)) {
                    totalTokens = value;
                }
                continue;
            }
            if (TOKEN_USAGE_VALUE_PATTERN.matcher(line).matches()) {
                int value = parseTokenNumber(line);
                if (value > 0) {
                    sawUsageLine = true;
                    orderedNumbers.add(value);
                    continue;
                }
            }
            if (sawUsageLine) {
                break;
            }
            break;
        }
        if (totalTokens <= 0 && !orderedNumbers.isEmpty()) {
            if (orderedNumbers.size() == 1) {
                totalTokens = orderedNumbers.get(0);
            } else if (orderedNumbers.size() == 2) {
                promptTokens = promptTokens <= 0 ? orderedNumbers.get(0) : promptTokens;
                completionTokens = completionTokens <= 0 ? orderedNumbers.get(1) : completionTokens;
            } else {
                promptTokens = promptTokens <= 0 ? orderedNumbers.get(0) : promptTokens;
                completionTokens = completionTokens <= 0 ? orderedNumbers.get(1) : completionTokens;
                totalTokens = orderedNumbers.get(2);
            }
        }
        if (totalTokens <= 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        }
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return null;
        }
        boolean estimated = false;
        if (promptTokens <= 0 || completionTokens <= 0) {
            TokenUsageStats estimatedUsage = buildEstimatedBreakdown(promptText, completionText, totalTokens);
            if (promptTokens <= 0) {
                promptTokens = estimatedUsage.promptTokens();
                estimated = true;
            }
            if (completionTokens <= 0) {
                completionTokens = estimatedUsage.completionTokens();
                estimated = true;
            }
        }
        if (totalTokens <= 0) {
            totalTokens = promptTokens + completionTokens;
            estimated = true;
        }
        return new OpenAiTextClient.TokenUsage(
            Math.max(0, promptTokens),
            Math.max(0, completionTokens),
            Math.max(0, totalTokens),
            estimated
        );
    }

    private TokenUsageStats buildEstimatedBreakdown(String promptText, String completionText, int totalTokensHint) {
        int estimatedPrompt = estimateTokens(promptText);
        int estimatedCompletion = estimateTokens(completionText);
        int estimatedTotal = estimatedPrompt + estimatedCompletion;
        if (totalTokensHint <= 0 || estimatedTotal <= 0) {
            return new TokenUsageStats(estimatedPrompt, estimatedCompletion, estimatedTotal);
        }
        int prompt = (int) Math.round((double) totalTokensHint * estimatedPrompt / estimatedTotal);
        int completion = Math.max(0, totalTokensHint - prompt);
        return new TokenUsageStats(Math.max(0, prompt), completion, totalTokensHint);
    }

    private int parseTokenNumber(String raw) {
        String value = safe(raw).replace(",", "");
        if (value.isBlank()) {
            return 0;
        }
        Matcher matcher = TOKEN_USAGE_NUMBER_PATTERN.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(matcher.group(1).replace(",", "")));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int lastLineIndexContainsIgnoreCase(List<String> lines, String expectedSegment) {
        String target = safe(expectedSegment).toLowerCase(Locale.ROOT);
        if (target.isBlank()) {
            return -1;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = safe(lines.get(i)).toLowerCase(Locale.ROOT);
            if (line.contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private String extractLastJsonPayload(String output) {
        String text = safe(output);
        if (text.isBlank()) {
            return "";
        }
        String lastPayload = "";
        List<Character> stack = new ArrayList<>();
        int startIndex = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (startIndex < 0) {
                if (ch == '{' || ch == '[') {
                    startIndex = i;
                    stack.clear();
                    stack.add(ch == '{' ? '}' : ']');
                    inString = false;
                    escaped = false;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                stack.add('}');
                continue;
            }
            if (ch == '[') {
                stack.add(']');
                continue;
            }
            if (ch == '}' || ch == ']') {
                if (stack.isEmpty() || stack.get(stack.size() - 1) != ch) {
                    stack.clear();
                    startIndex = -1;
                    inString = false;
                    escaped = false;
                    continue;
                }
                stack.remove(stack.size() - 1);
                if (stack.isEmpty()) {
                    lastPayload = text.substring(startIndex, i + 1).trim();
                    startIndex = -1;
                    inString = false;
                    escaped = false;
                }
            }
        }
        return lastPayload;
    }

    private int estimateTokens(String text) {
        int length = safe(text).length();
        if (length <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(length / 4.0));
    }

    private String summarizeCommandHead(List<String> commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return "";
        }
        int max = Math.min(4, commandLine.size());
        return String.join(" ", commandLine.subList(0, max));
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    private record CliInvocation(List<String> commandLine, boolean writePromptToStdin) {
    }

    private record TokenUsageStats(int promptTokens, int completionTokens, int totalTokens) {
    }
}
