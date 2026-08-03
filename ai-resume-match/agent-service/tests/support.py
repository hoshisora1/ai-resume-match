from __future__ import annotations

from typing import Any

from agent_service.models import AssistantTurn


class ScriptedModel:
    def __init__(self, turns: list[AssistantTurn]) -> None:
        self._turns = list(turns)
        self.messages_seen: list[list[dict[str, Any]]] = []

    @property
    def model_name(self) -> str:
        return "scripted-agent-model"

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
    ) -> AssistantTurn:
        self.messages_seen.append(list(messages))
        if not self._turns:
            return AssistantTurn(content="done")
        return self._turns.pop(0)
