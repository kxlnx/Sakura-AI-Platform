package com.yupi.yuaiagent.archived.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于文件持久化的对话记忆
 */

// 就说ai生成的
public class FileBasedChatMemory implements ChatMemory {

    // 文件存储基础目录
    private final String BASE_DIR;
    // 全局Kryo序列化实例
    private static final Kryo kryo = new Kryo();

    // 静态初始化Kryo配置
    static {
        // 关闭强制注册类，允许序列化任意类
        kryo.setRegistrationRequired(false);
        // 设置实例化策略，支持序列化无默认构造函数的类
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    // 构造对象时，指定文件保存目录
    public FileBasedChatMemory(String dir) {
        this.BASE_DIR = dir;
        File baseDir = new File(dir);
        // 如果目录不存在则创建
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 获取或创建对话消息列表
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        // 添加新消息到列表
        conversationMessages.addAll(messages);
        // 保存到文件
        saveConversation(conversationId, conversationMessages);
    }

    @Override
    public List<Message> get(String conversationId) {
        // 获取对话所有消息
        return getOrCreateConversation(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        // 获取对话对应的文件
        File file = getConversationFile(conversationId);
        // 如果文件存在则删除，清空记忆
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 获取或创建对话消息列表
     */
    private List<Message> getOrCreateConversation(String conversationId) {
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        // 如果文件已存在，从文件反序列化读取消息
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                messages = kryo.readObject(input, ArrayList.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return messages;
    }

    /**
     * 保存对话消息到文件
     */
    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            // 使用Kryo序列化对象并写入文件
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据对话ID获取对应的存储文件
     */
    private File getConversationFile(String conversationId) {
        return new File(BASE_DIR, conversationId + ".kryo");
    }
}
