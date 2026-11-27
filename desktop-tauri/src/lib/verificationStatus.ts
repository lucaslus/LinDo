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
      if (state.stage === "recovering") return state;
      return { ...state, attentionNeeded: action.needed };
    case "manual":
      return { ...state, manualBusy: action.busy };
    case "hidePill":
      return { ...state, pillVisible: false };
    case "verified":
      return { ...initialVerificationStatus, round: state.round };
  }
}
