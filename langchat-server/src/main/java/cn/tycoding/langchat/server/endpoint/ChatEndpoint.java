/*
 * Copyright (c) 2024 LangChat. TyCoding All Rights Reserved.
 *
 * Licensed under the GNU Affero General Public License, Version 3 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gnu.org/licenses/agpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.tycoding.langchat.server.endpoint;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import cn.tycoding.langchat.ai.biz.entity.AigcApp;
import cn.tycoding.langchat.ai.biz.entity.AigcMessage;
import cn.tycoding.langchat.ai.biz.entity.AigcModel;
import cn.tycoding.langchat.ai.biz.service.AigcAppService;
import cn.tycoding.langchat.ai.biz.service.AigcMessageService;
import cn.tycoding.langchat.ai.biz.service.AigcModelService;
import cn.tycoding.langchat.ai.core.service.impl.PersistentChatMemoryStore;
import cn.tycoding.langchat.common.ai.dto.ChatReq;
import cn.tycoding.langchat.common.ai.dto.ChatRes;
import cn.tycoding.langchat.common.ai.dto.ImageR;
import cn.tycoding.langchat.common.ai.dto.PromptConst;
import cn.tycoding.langchat.common.ai.properties.ChatProps;
import cn.tycoding.langchat.common.ai.utils.PromptUtil;
import cn.tycoding.langchat.common.ai.utils.StreamEmitter;
import cn.tycoding.langchat.common.core.constant.RoleEnum;
import cn.tycoding.langchat.common.core.utils.CommonResponse;
import cn.tycoding.langchat.server.service.ChatService;
import cn.tycoding.langchat.upms.utils.AuthUtil;
import com.alibaba.fastjson.JSON;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * @author tycoding
 * @since 2024/1/30
 */
@Slf4j
@RequestMapping("/aigc")
@RestController
@AllArgsConstructor
public class ChatEndpoint {

    private final ChatService chatService;
    private final AigcMessageService messageService;
    private final AigcModelService aigcModelService;
    private final AigcAppService appService;
    private final ChatProps chatProps;

    @PostMapping("/chat/completions")
    @SaCheckPermission("chat:completions")
    public SseEmitter chat(@RequestBody ChatReq req) {
        log.info("ChatReq: {}", JSON.toJSONString(req));
        StreamEmitter emitter = new StreamEmitter();
        req.setEmitter(emitter);
        req.setUserId(AuthUtil.getUserId());
        req.setUsername(AuthUtil.getUsername());

        Mono.fromRunnable(() -> chatService.chat(req))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> {
                    log.error("chat error in conversation id: {} -> {}", req.getConversationId(), error.toString());
                })
                .doOnSuccess(
                        res -> {
                            log.info("chat success in conversation id: {} -> {}", req.getConversationId(), res);
                        }
                )
                .subscribe();
        return emitter.get();
    }

    @GetMapping("/app/info")
    public CommonResponse<AigcApp> appInfo(@RequestParam String appId, String conversationId) {
        AigcApp app = appService.getById(appId);
        if (StrUtil.isBlank(conversationId)) {
            conversationId = app.getId();
        }

        if (StrUtil.isNotBlank(app.getPrompt())) {
            // initialize chat memory
            SystemMessage message = new SystemMessage(app.getPrompt());
            PersistentChatMemoryStore.init(conversationId, message);
        }

        return CommonResponse.ok(app);
    }

    @GetMapping("/chat/messages/{conversationId}")
    public CommonResponse messages(@PathVariable String conversationId) {
        List<AigcMessage> list = messageService.getMessages(conversationId, String.valueOf(AuthUtil.getUserId()));

        // initialize chat memory
        List<ChatMessage> chatMessages = new ArrayList<>();
        list.forEach(item -> {
            if (chatMessages.size() >= chatProps.getMemoryMaxMessage()) {
                return;
            }
            if (item.getRole().equals(RoleEnum.ASSISTANT.getName())) {
                chatMessages.add(new AiMessage(item.getMessage()));
            } else {
                chatMessages.add(new UserMessage(item.getMessage()));
            }
        });
        PersistentChatMemoryStore.init(conversationId, chatMessages);
        return CommonResponse.ok(list);
    }

    @DeleteMapping("/chat/messages/clean/{conversationId}")
    @SaCheckPermission("chat:messages:clean")
    public CommonResponse cleanMessage(@PathVariable String conversationId) {
        messageService.clearMessage(conversationId);

        // clean chat memory
        PersistentChatMemoryStore.clean(conversationId);
        return CommonResponse.ok();
    }

    @PostMapping("/chat/mindmap")
    public CommonResponse mindmap(@RequestBody ChatReq req) {
        req.setPrompt(PromptUtil.build(req.getMessage(), PromptConst.MINDMAP));
        return CommonResponse.ok(new ChatRes(chatService.text(req)));
    }

    @PostMapping("/chat/image")
    public CommonResponse image(@RequestBody ImageR req) {
        req.setPrompt(PromptUtil.build(req.getMessage(), PromptConst.IMAGE));
        return CommonResponse.ok(chatService.image(req));
    }

    @GetMapping("/chat/getImageModels")
    public CommonResponse<List<AigcModel>> getImageModels() {
        List<AigcModel> list = aigcModelService.getImageModels();
        list.forEach(i -> {
            i.setApiKey(null);
            i.setSecretKey(null);
        });
        return CommonResponse.ok(list);
    }
}
