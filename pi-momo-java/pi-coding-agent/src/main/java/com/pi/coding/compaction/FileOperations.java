package com.pi.coding.compaction;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件操作追踪器，记录会话期间的文件操作，用于生成压缩摘要中的文件列表。
 *
 * <p>追踪三类文件操作：
 * <ul>
 *   <li>读取（read） - 文件被读取但未被修改</li>
 *   <li>写入（write） - 文件被新建或完全覆盖写入</li>
 *   <li>编辑（edit） - 文件被部分编辑修改</li>
 * </ul>
 *
 * <p>文件操作信息会追加到压缩摘要中，使用 XML 标签格式呈现，
 * 以便后续轮次了解哪些文件已被操作过，避免重复工作。
 *
 * <p><b>验证需求: 3.12</b>
 */
public final class FileOperations {

    /** 仅读取的文件路径集合 */
    private final Set<String> read = new HashSet<>();
    /** 新写入的文件路径集合 */
    private final Set<String> written = new HashSet<>();
    /** 编辑过的文件路径集合 */
    private final Set<String> edited = new HashSet<>();

    /**
     * 创建空的文件操作追踪器。
     */
    public FileOperations() {
    }

    /**
     * 添加一个被读取的文件路径。
     *
     * @param path 文件路径，空值或空字符串会被忽略
     */
    public void addRead(String path) {
        if (path != null && !path.isEmpty()) {
            read.add(path);
        }
    }

    /**
     * 添加一个被写入的文件路径。
     *
     * @param path 文件路径，空值或空字符串会被忽略
     */
    public void addWritten(String path) {
        if (path != null && !path.isEmpty()) {
            written.add(path);
        }
    }

    /**
     * 添加一个被编辑的文件路径。
     *
     * @param path 文件路径，空值或空字符串会被忽略
     */
    public void addEdited(String path) {
        if (path != null && !path.isEmpty()) {
            edited.add(path);
        }
    }

    /**
     * 获取仅读取的文件路径集合（不可修改视图）。
     *
     * @return 只读文件路径的不可修改集合
     */
    public Set<String> getRead() {
        return Collections.unmodifiableSet(read);
    }

    /**
     * 获取新写入的文件路径集合（不可修改视图）。
     *
     * @return 写入文件路径的不可修改集合
     */
    public Set<String> getWritten() {
        return Collections.unmodifiableSet(written);
    }

    /**
     * 获取编辑过的文件路径集合（不可修改视图）。
     *
     * @return 编辑文件路径的不可修改集合
     */
    public Set<String> getEdited() {
        return Collections.unmodifiableSet(edited);
    }

    /**
     * 合并另一个 FileOperations 追踪器中的操作记录到当前追踪器。
     * 用于累积多个轮次或分支的文件操作信息。
     *
     * @param other 另一个文件操作追踪器，null 安全
     */
    public void merge(FileOperations other) {
        if (other != null) {
            read.addAll(other.read);
            written.addAll(other.written);
            edited.addAll(other.edited);
        }
    }

    /**
     * 检查是否没有任何操作被追踪。
     *
     * @return 如果三种操作集合都为空则返回 true
     */
    public boolean isEmpty() {
        return read.isEmpty() && written.isEmpty() && edited.isEmpty();
    }
}