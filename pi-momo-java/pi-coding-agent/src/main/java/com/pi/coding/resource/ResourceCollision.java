package com.pi.coding.resource;

/**
 * 资源名称冲突信息。
 *
 * <p>当从多个路径加载的资源（如 Skills 或 Prompt 模板）出现名称重复时，
 * 会生成此记录描述冲突详情。遵循"先到先得"原则，第一个加载的资源胜出，
 * 后续同名的资源被忽略。
 *
 * <p>冲突信息包含：
 * <ul>
 *   <li>资源类型（如 "skill" 或 "prompt"）</li>
 *   <li>冲突的名称</li>
 *   <li>胜出资源的路径（保留）</li>
 *   <li>失败资源的路径（被忽略）</li>
 * </ul>
 *
 * @param resourceType 资源类型，如 "skill" 或 "prompt"
 * @param name         冲突的资源名称
 * @param winnerPath   胜出资源的文件路径（被保留的资源）
 * @param loserPath    失败资源的文件路径（被忽略的资源）
 */
public record ResourceCollision(
    String resourceType,
    String name,
    String winnerPath,
    String loserPath
) {
    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果任何参数为空
     */
    public ResourceCollision {
        if (resourceType == null || resourceType.isEmpty()) {
            throw new IllegalArgumentException("resourceType 不能为空");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (winnerPath == null || winnerPath.isEmpty()) {
            throw new IllegalArgumentException("winnerPath 不能为空");
        }
        if (loserPath == null || loserPath.isEmpty()) {
            throw new IllegalArgumentException("loserPath 不能为空");
        }
    }
}
