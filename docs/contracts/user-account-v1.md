# 用户账号功能契约 v1（2026-10-25）

本契约复用现有服务端账号能力，不包含验证码、找回密码或第三方依赖。

## HTTP 与字段语义

全部接口使用 `Authorization: Bearer <accessToken>`，无刷新令牌。用户资料继续使用现有 `UserProfile`（accountId 为十进制字符串，phone/email 可为 null）；API 返回现有 `AuthResult<T>`，不自动修改全局会话、不弹气泡、不自动重试。

| 操作 | 请求 | 成功 | 说明 |
| --- | --- | --- | --- |
| 读取当前资料 | `GET /api/v1/users/me` | 200 UserProfile | `AuthApi.getCurrentUser(token)` |
| 更新/解绑联系方式 | `PATCH /api/v1/users/me` | 200 UserProfile | 仅接受 phone、email；完整返回规范化后的真实资料 |
| 修改密码 | `PATCH /api/v1/users/me/password` | 204 空体 | oldPassword、newPassword、confirmPassword；不签发新 token |

联系方式字段缺省或 `undefined` 表示保持，显式 `null` 表示解绑；空串或纯空白是错误，不能隐式转换为解绑。`{}` 只读取现有资料，不修改更新时间。单请求内两个字段原子更新，任一冲突回滚全部变更。username、role、accountId、存在性标志及其他未知字段均拒绝。

手机号由服务端去首尾空白；国内 `1[3-9]` 开头的 11 位数字补 `+86`；国际号码按 `^\+[1-9]\d{1,14}$`。邮箱去首尾空白并转小写；仅接受现有保守 ASCII 格式（本地部分字母数字组以 `. _ % + -` 分隔，域名至少两段，标签不以连字符开头或结尾）；总长不超过 254，本地部分 UTF-8 不超过 64 字节，每个域名标签不超过 63 字符。手机号和邮箱各自全局唯一。绑定只记录联系方式，不表示已验证所有权，UI 不展示“已认证”。

所有密码原样传递，不 trim。旧密码 1～64 个 Unicode 码点，新密码 8～64 个码点，两者 UTF-8 均不超过 72 字节；确认密码须非空且与新密码完全一致。现有契约允许新旧密码相同。

## common 调用签名

```typescript
// AuthModels.ets；字段只给需要修改的项目赋值。
class UpdateProfileInput {
  phone?: string | null;
  email?: string | null;
}

// AuthApi.ets，复用现有实例和地址。
updateProfile(token: string, input: UpdateProfileInput): Promise<AuthResult<UserProfile>>;
changePassword(token: string, oldPassword: string, newPassword: string,
  confirmPassword: string): Promise<AuthResult<boolean>>; // 204 => true

// AuthValidation.ets；返回字段名到错误文案，空对象表示本地校验通过。
validate_profile_update(input: UpdateProfileInput): Record<string, string>;
validate_change_password(oldPassword: string, newPassword: string,
  confirmPassword: string): Record<string, string>;

// AuthSession.ets；不改变 token 与原到期时刻。
update_user_if_token(token: string, user: UserProfile, nowMillis: number = Date.now()): boolean;
// 继续使用既有 matches_access_token / clear_session_if_token。
```

本地校验仅阻止明显不合法输入；服务端是规范化、唯一性、口令校验和会话有效性的最终依据。PATCH 不提交 username/role 等字段。解析继续复用现有严格 UserProfile 解析，不以不完整对象覆盖会话。`update_user_if_token` 仅在 token 匹配、会话未过期、accountId 相同时生效；迟到响应不得恢复或覆盖其他登录。

`common/Index.ets` 导出 `UpdateProfileInput`、`validate_profile_update` 与 `validate_change_password`；AuthApi/AuthSession 出口沿用现有接口。

## 错误与会话规则

| 状态/业务码 | 页面处理 |
| --- | --- |
| 400 A0400 | 使用 fieldErrors 原位提示；旧密码不匹配落到 oldPassword，文案“旧密码错误”；保留输入 |
| 400 A0427 | 请求格式/未知字段错误，提示无法提交并允许检查/重试，不显示原始异常 |
| 409 A0154 | phone 提示“该手机号已绑定”，保留原资料 |
| 409 A0155 | email 提示“该邮箱已绑定”，保留原资料 |
| 401 A0200 | 当前受保护会话无效；仅清除请求所属 token 后转登录 |
| 409 A0444 | 密码已变更的并发冲突；仅清除请求所属 token 后转登录 |
| 503 C0001 | 服务暂不可用，保留表单并允许用户主动重试 |
| 网络/协议错误 | 无成功确认；不自动清会话、不自动重发写操作，保留字段错误与草稿 |

改密事务在同一条件 SQL 中更新密码哈希并递增 token_version。认证过滤器每请求复核 Redis 会话与数据库版本，所以当前及此前所有旧 token 在后续请求中均返回 401；成功不签发替换 token。UI 确认 204 后，即使已经离开表单，也应先按请求 token 归属清除旧会话并通知 root，再以页面/请求序号过滤表单 UI 更新；不能因离页丢弃服务端已确认的会话失效。使用 `clear_session_if_token(token)` 或 root 的等效归属判断，不追加 logout 请求。root 完成清过时 scope、转登录后，在登录 scope 显示一次“密码已修改，请使用新密码登录”成功气泡；不能先在旧账号 scope 发气泡后立即清掉，也不能为提示保留过期 token。新登录须使用新密码。旧密码错误不改变任何会话；并发改密可能由过滤器先返回 401，也可能由事务返回 409 A0444。

改密网络中断可能发生在服务端已提交之后，不能声称未修改，也不能声称成功；页面提示“无法确认修改结果，可重新登录确认”，保留本地输入且不自动重发。页面退出/账号变化后销毁口令输入；口令不写日志、不持久化。联系方式更新成功后使用响应资料更新当前页面及 `update_user_if_token`，不重新 save_session 或延长有效期。

## 验证范围

验证需覆盖联系方式缺省/null/格式/冲突/事务、改密旧密码/确认/旧 token/并发，以及手机和平板上的会话迟到响应。单元替身与静态检查不能替代真实后端、ArkTS 编译或设备验收。
