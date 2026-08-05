package com.micklab.llamachat;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExpertPromptStore {
    public static final String FEATURE_WEB_SEARCH = "web_search";

    public static final String KEY_EXPERT_ROUTER = "expertRouterPrompt";
    public static final String KEY_WEB_SEARCH_KEYWORD = "webSearchKeywordPrompt";
    public static final String KEY_WEB_SEARCH_SYSTEM = "webSearchSystemPrompt";

    private static final String FILE_NAME = "expert_prompts.json";

    private static final PromptSpec[] PROMPT_SPECS = new PromptSpec[]{
            new PromptSpec(
                    KEY_EXPERT_ROUTER,
                    "Expert route check",
                    "エキスパート要否判定",
                    "あなたの役割は、ユーザー入力に対して利用すべき補助機能だけを選別することです。\n"
                            + "補助機能の候補は毎回、呼び方や並び順を変えて提示されます。表示名に引きずられず、用途説明から判断してください。\n\n"
                            + "利用可能な候補:\n"
                            + "{{enabled_features_block}}\n\n"
                            + "出力は JSON オブジェクト 1 つのみで、前後に説明文を付けないでください。\n"
                            + "{\"required_functions\":[\"web_search\"],\"note\":\"short reason\"}\n\n"
                            + "制約:\n"
                            + "- required_functions には必要な機能だけを必要順で入れる\n"
                            + "- 不要なら空配列 []\n"
                            + "- 機能IDは web_search だけを使う\n"
                            + "- note は 1 文で簡潔に書く\n\n"
                            + "ユーザー入力:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_WEB_SEARCH_KEYWORD,
                    "Web search keyword extraction",
                    "Web検索キーワード抽出",
                    "あなたの役割は「ユーザーの質問がインターネット検索を必要とするか判定し、必要なら検索キーワードを抽出する」ことです。\n\n"
                            + "出力は必ず次のどちらか一つだけにしてください:\n\n"
                            + "1. 検索が必要な場合:\nSEARCH: <検索キーワード>\n\n"
                            + "2. 検索が不要な場合:\nNONE\n\n"
                            + "制約:\n"
                            + "- 説明文や理由を書かない\n"
                            + "- 箇条書きや追加情報を含めない\n"
                            + "- キーワードは短く、検索エンジンで使える語句のみ\n"
                            + "- 複数キーワードが必要な場合はスペース区切りで 1 行にまとめる\n"
                            + "- 出力形式を絶対に変えない\n\n"
                            + "ユーザーの質問:\n"
                            + "{{user_input}}"
            ),
            new PromptSpec(
                    KEY_WEB_SEARCH_SYSTEM,
                    "Web search system prompt",
                    "Web検索システムプロンプト",
                    "You are a search-augmented assistant. When the user provides SEARCH_RESULTS, you must read them and base your answer strictly on that information."
            )
    };

    private ExpertPromptStore() {
    }

    public static List<PromptSpec> getPromptSpecs() {
        List<PromptSpec> visibleSpecs = new ArrayList<>();
        for (PromptSpec spec : PROMPT_SPECS) {
            if (!KEY_EXPERT_ROUTER.equals(spec.getKey())) {
                visibleSpecs.add(spec);
            }
        }
        return Collections.unmodifiableList(visibleSpecs);
    }

    public static PromptSpec getPromptSpec(String key) {
        for (PromptSpec spec : PROMPT_SPECS) {
            if (spec.getKey().equals(key)) {
                return spec;
            }
        }
        return PROMPT_SPECS[0];
    }

    public static synchronized String getPrompt(Context context, String key) {
        PromptSpec spec = getPromptSpec(key);
        JSONObject json = readJson(context);
        String saved = json.optString(key, "");
        return TextUtils.isEmpty(saved) ? spec.getDefaultValue() : saved;
    }

    public static synchronized void savePrompt(Context context, String key, String value) {
        JSONObject json = readJson(context);
        try {
            json.put(key, value == null ? "" : value);
            writeJson(context, json);
        } catch (Exception ignored) {
        }
    }

    public static String getSearchSystemPrompt(Context context) {
        return getPrompt(context, KEY_WEB_SEARCH_SYSTEM).trim();
    }

    public static String buildExpertRouterPrompt(Context context, List<String> enabledFeatures, String userInput) {
        Map<String, String> values = new HashMap<>();
        values.put("enabled_features_block", buildEnabledFeaturesBlock(enabledFeatures, userInput));
        values.put("user_input", safe(userInput));
        return applyTemplate(getPrompt(context, KEY_EXPERT_ROUTER), values);
    }

    public static String buildWebSearchKeywordPrompt(Context context, String userInput) {
        Map<String, String> values = new HashMap<>();
        values.put("user_input", safe(userInput));
        return applyTemplate(getPrompt(context, KEY_WEB_SEARCH_KEYWORD), values);
    }

    public static ExpertRouteDecision parseExpertRouteDecision(String rawResponse, List<String> enabledFeatures) {
        List<String> allowed = enabledFeatures == null ? Collections.emptyList() : enabledFeatures;
        try {
            JSONObject outer = new JSONObject(rawResponse);
            String inner = outer.optString("response", "").trim();
            if (inner.isEmpty()) {
                return new ExpertRouteDecision(Collections.emptyList(), "");
            }
            JSONObject json = new JSONObject(inner);
            JSONArray required = json.optJSONArray("required_functions");
            List<String> ordered = new ArrayList<>();
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    String id = required.optString(i, "").trim();
                    if (!ordered.contains(id) && allowed.contains(id)) {
                        ordered.add(id);
                    }
                }
            }
            return new ExpertRouteDecision(ordered, json.optString("note", ""));
        } catch (Exception ignored) {
            return new ExpertRouteDecision(Collections.emptyList(), "");
        }
    }

    public static ExpertRouteDecision createRouteDecision(List<String> orderedFunctionIds, String note) {
        return new ExpertRouteDecision(orderedFunctionIds, note);
    }

    private static String buildEnabledFeaturesBlock(List<String> enabledFeatures, String userInput) {
        List<FeatureDescriptor> descriptors = new ArrayList<>();
        if (enabledFeatures != null) {
            for (String id : enabledFeatures) {
                FeatureDescriptor descriptor = FeatureDescriptor.forId(id);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
        }
        if (descriptors.isEmpty()) {
            return "- (none)";
        }
        int seed = Math.abs((userInput == null ? "" : userInput).hashCode());
        if (descriptors.size() > 1) {
            Collections.rotate(descriptors, seed % descriptors.size());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < descriptors.size(); i++) {
            FeatureDescriptor descriptor = descriptors.get(i);
            String alias = descriptor.aliases[(seed + i) % descriptor.aliases.length];
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- id: ").append(descriptor.id)
                    .append(" / 呼称: ").append(alias)
                    .append(" / 用途: ").append(descriptor.description);
        }
        return sb.toString();
    }

    private static String applyTemplate(String template, Map<String, String> values) {
        String resolved = template == null ? "" : template;
        if (values == null || values.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return resolved;
    }

    private static synchronized JSONObject readJson(Context context) {
        JSONObject json = new JSONObject();
        if (context == null) {
            return json;
        }
        try (FileInputStream fis = context.openFileInput(FILE_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (sb.length() > 0) {
                json = new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    private static synchronized void writeJson(Context context, JSONObject json) {
        if (context == null || json == null) {
            return;
        }
        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
            fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class PromptSpec {
        private final String key;
        private final String labelEn;
        private final String labelJa;
        private final String defaultValue;

        private PromptSpec(String key, String labelEn, String labelJa, String defaultValue) {
            this.key = key;
            this.labelEn = labelEn;
            this.labelJa = labelJa;
            this.defaultValue = defaultValue;
        }

        public String getKey() {
            return key;
        }

        public String getLabel(String appLanguage) {
            return "ja".equals(appLanguage) ? labelJa : labelEn;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    public static final class ExpertRouteDecision {
        private final List<String> orderedFunctionIds;
        private final String note;

        private ExpertRouteDecision(List<String> orderedFunctionIds, String note) {
            this.orderedFunctionIds = orderedFunctionIds == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(orderedFunctionIds));
            this.note = note == null ? "" : note;
        }

        public List<String> getOrderedFunctionIds() {
            return orderedFunctionIds;
        }

        public String getNote() {
            return note;
        }

        public boolean requires(String featureId) {
            return orderedFunctionIds.contains(featureId);
        }
    }

    private static final class FeatureDescriptor {
        private static final Map<String, FeatureDescriptor> BY_ID;

        static {
            Map<String, FeatureDescriptor> values = new LinkedHashMap<>();
            values.put(FEATURE_WEB_SEARCH, new FeatureDescriptor(
                    FEATURE_WEB_SEARCH,
                    new String[]{"外部情報の確認", "オンライン調査", "公開Web参照"},
                    "公開Web上の最新情報や事実確認、参考情報の補完が必要な場合に使う。"
            ));
            BY_ID = Collections.unmodifiableMap(values);
        }

        private final String id;
        private final String[] aliases;
        private final String description;

        private FeatureDescriptor(String id, String[] aliases, String description) {
            this.id = id;
            this.aliases = aliases;
            this.description = description;
        }

        private static FeatureDescriptor forId(String id) {
            return BY_ID.get(id);
        }
    }
}
