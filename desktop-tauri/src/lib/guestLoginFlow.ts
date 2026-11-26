import type { CategoryMeta } from "./types";

export interface GuestLoginPromptPayload {
  title: string;
  subtitle: string;
}

export const DEFAULT_GUEST_LOGIN_PROMPT: GuestLoginPromptPayload = {
  title: "登录 LINUX DO",
  subtitle: "选择登录方式",
};

export const MESSAGES_GUEST_LOGIN_PROMPT: GuestLoginPromptPayload = {
  title: "登录后查看消息",
  subtitle: "查看社区通知与站内私信",
};

export const PROFILE_GUEST_LOGIN_PROMPT: GuestLoginPromptPayload = {
  title: "登录 LINUX DO",
  subtitle: "同步个人资料、收藏与通知",
};

export function buildRestrictedCategoryGuestPrompt(categoryName: string): GuestLoginPromptPayload {
  return {
    title: `登录后查看「${categoryName}」`,
    subtitle: "该版块仅对登录成员开放",
  };
}

export function buildExpiredSessionGuestPrompt(
  expiredUsername?: string | null
): GuestLoginPromptPayload {
  const clean = (expiredUsername || "").trim();
  return {
    title: "重新登录",
    subtitle: clean ? `@${clean} 登录已过期，请选择登录方式` : "登录已过期，请选择登录方式",
  };
}

export type RestrictedCategoryDecision =
  | { blocked: true; prompt: GuestLoginPromptPayload }
  | { blocked: false; categoryId: number | null };

/**
 * Shared restricted-category guest guard reused across wide Home, narrow Home,
 * TopicReader category links, and Discover category cards.
 */
export function resolveRestrictedCategorySelection(
  categoryId: number | null,
  categories: readonly CategoryMeta[],
  isAuthorized: boolean
): RestrictedCategoryDecision {
  if (categoryId !== null && !isAuthorized) {
    const cat = categories.find((c) => c.id === categoryId);
    if (cat?.readRestricted) {
      return {
        blocked: true,
        prompt: buildRestrictedCategoryGuestPrompt(cat.name),
      };
    }
  }
  return { blocked: false, categoryId };
}

/**
 * Account management entry routes to GuestLoginGuideModal when guest has 0 saved accounts,
 * and opens AuthModal when authorized or saved accounts exist for switching/management.
 */
export function resolveAccountManagementTarget(
  isAuthorized: boolean,
  savedAccountCount: number
): "guest_guide" | "auth_modal" {
  return !isAuthorized && savedAccountCount <= 0 ? "guest_guide" : "auth_modal";
}
