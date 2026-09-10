"""Pydantic models mirroring spec/schemas (v1). Server keeps payload verbatim."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

DayOfWeek = Literal["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
HolidayPolicy = Literal["exclude", "include", "only"]
TaskSource = Literal["google_tasks", "agent", "manual"]
TaskStatus = Literal["needsAction", "completed"]


class Recurrence(BaseModel):
    daysOfWeek: list[DayOfWeek] = Field(default_factory=list)
    holiday: HolidayPolicy = "exclude"


class RoutineBlock(BaseModel):
    id: str
    start: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    end: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    title: str
    note: str = ""


class RoutineProfile(BaseModel):
    schemaVersion: int = 1
    routineAddress: str = Field(pattern=r"^rt_[0-9a-fA-F]{64}$")
    name: str
    lastModified: str
    recurrence: Recurrence
    blocks: list[RoutineBlock] = Field(default_factory=list)


class RoutineMeta(BaseModel):
    routineAddress: str
    name: str
    lastModified: str
    schemaVersion: int = 1
    deleted: bool = False


class Task(BaseModel):
    schemaVersion: int = 1
    id: str
    source: TaskSource
    externalId: str | None = None
    title: str
    at: str
    allDay: bool = False
    status: TaskStatus = "needsAction"
    lastModified: str
    deleted: bool = False


class ErrorBody(BaseModel):
    code: str
    message: str


class ErrorEnvelope(BaseModel):
    error: ErrorBody
