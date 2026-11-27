export const FORUM_CONNECTION_PENDING = "论坛连接尚未恢复";

export function forumRecoveryAction(message: string | null) {
  if (message === FORUM_CONNECTION_PENDING) return "重试连接";
  return message && /验证|challenge/.test(message) ? "去验证" : null;
}

export type VerifyPillEvent = {
  round: number;
  visible: boolean;
  stage: "checking" | "panel" | "recovering" | "done" | "unavailable";
};

type VerificationStatus = {
  round: number;
  stage: VerifyPillEvent["stage"];
  attentionNeeded: boolean;
  manualBusy: boolean;
  pillVisible: boolean;
};

export const initialVerificationStatus: VerificationStatus = {
  round: 0,
  stage: "done",
  attentionNeeded: false,
  manualBusy: false,
  pillVisible: false,
};

type Action =
  | { type: "progress"; event: VerifyPillEvent }
  | { type: "attention"; needed: boolean }
  | { type: "manual"; busy: boolean }
  | { type: "hidePill" }
  | { type: "verified" };

export function verificationStatus(state: VerificationStatus, action: Action): VerificationStatus {
  switch (action.type) {
    case "progress": {
      const { round, stage, visible } = action.event;
      if (round < state.round) return state;
      if (stage === "recovering" || stage === "unavailable") {
        return { round, stage, attentionNeeded: false, manualBusy: false, pillVisible: false };
      }
      return { ...state, round, stage, pillVisible: visible };
    }
    case "attention":
      if (state.stage === "recovering" || state.stage === "unavailable") return state;
      return { ...state, attentionNeeded: action.needed };
    case "manual":
      return { ...state, manualBusy: action.busy };
    case "hidePill":
      return { ...state, pillVisible: false };
    case "verified":
      return { ...initialVerificationStatus, round: state.round };
  }
}
