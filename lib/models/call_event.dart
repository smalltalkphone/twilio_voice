enum CallEvent {
  incoming,
  ringing,
  connected,
  reconnected,
  reconnecting,
  callEnded,
  unhold,
  hold,
  unmute,
  mute,
  speakerOn,
  speakerOff,
  bluetoothOn,
  bluetoothOff,
  log,
  permission,
  declined,
  answer,
  missedCall,
  returningCall,

  /// The call failed to connect — Twilio's `onConnectFailure`, surfaced as a
  /// typed event instead of an untyped log line.
  ///
  /// Before this, the native layer flattened every connect failure into
  /// `logEvent("Call Error: <code>, <message>")` and [parseCallEvent] decoded
  /// only 31600 / 31603 / 31486 (busy) into [declined]; everything else — 31404,
  /// 31005, 13247 — fell through to [log], whose payload is discarded. So a
  /// failed call produced NO observable event at all, and an app had no way to
  /// tell "failed" from "still trying".
  connectFailure,
}
