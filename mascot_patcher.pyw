#!/usr/bin/env python3
"""
Mascot XML Batch Editor
A desktop tool for batch-editing Shimeji mascot actions.xml and behaviors.xml files.
"""

import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext
import os
import re
import json
import threading

# ─────────────────────────────────────────────
#  PATCH DEFINITIONS
#  Each patch is a dict describing one operation.
#  Add new patches here to extend the tool.
# ─────────────────────────────────────────────

# ── Helpers ──────────────────────────────────

def _find_action_end(content, start_idx):
    """Given the index of an <Action ...> opening tag, return the index just after its closing </Action>."""
    depth = 0
    i = start_idx
    while i < len(content):
        if content[i:i+7] == '<Action' and (i + 7 >= len(content) or content[i+7] in ' \t\n\r'):
            depth += 1
            i += 7
        elif content[i:i+9] == '</Action>':
            depth -= 1
            if depth == 0:
                return i + 9
            i += 9
        else:
            i += 1
    return -1


def _has_action_def(content, name):
    return f'<Action Name="{name}"' in content


def _has_behavior_def(content, name):
    return f'<Behavior Name="{name}"' in content


# ─────────────────────────────────────────────
#  PATCH: Add / Fix JumpToCursor
# ─────────────────────────────────────────────

# Canonical fingerprint — a unique substring that only appears in the CURRENT
# version of JumpToCursor. If this is absent the block is missing or outdated.
# Uses comment text (not variable values) so it survives numeric tweaks.
_JUMPTOCURSOR_FINGERPRINT = "Intercepted cursor during fall → grab"

NEW_JUMP_TO_CURSOR = '''
		<Action Name="JumpToCursor" Type="Sequence" Loop="false">
			<!-- Jump toward cursor -->
			<ActionReference Name="Jumping"
				TargetX="${mascot.environment.cursor.x}"
				TargetY="${mascot.environment.cursor.y - Math.round(10 * mascot.currentScale)}"
				Duration="${100 + Math.random() * 100}"  />
			<!-- Loop until we reach cursor -->
			<Action Type="Sequence">
				<Action Type="Select">
	
					<!-- Hit a wall → cling to it -->
					<Action Type="Sequence"
						Condition="${mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor) || mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor)}">
						<ActionReference Name="GrabWall" 
						Duration="1" />
					</Action>
					<!-- Landed on top of IE window or Hit the floor → stand on it -->
					<Action Type="Sequence"
						Condition="${mascot.environment.activeIE.topBorder.isOn(mascot.anchor) || mascot.environment.workArea.bottomBorder.isOn(mascot.anchor)}">
						<ActionReference Name="Stand" 
						Duration="1" />
					</Action>
					<!-- Cursor is above → jump again toward new cursor position -->
					<Action Type="Sequence"
						Condition="${mascot.anchor.y >= mascot.environment.cursor.y &amp;&amp; mascot.environment.cursor.y &lt; mascot.anchor.y - 80}">
						<ActionReference Name="Jumping"
							TargetX="${mascot.environment.cursor.x}"
							TargetY="${mascot.environment.cursor.y - 80}" 
							Duration="${100 + Math.random() * 100}" />
					</Action>
					<!-- Reached cursor → grab -->
					<Action Type="Sequence"
						Condition="${mascot.anchor.y >= mascot.environment.cursor.y}">
						<ActionReference Name="Dragged" />
					</Action>
					<!-- Otherwise fall towards cursor -->
					<Action Type="Sequence">
						<ActionReference Name="Falling"
							InitialVX="${(mascot.environment.cursor.x - mascot.anchor.x) * 0.10}"
							InitialVY="${(mascot.environment.cursor.y - mascot.anchor.y) * 0.10}"
							Duration="${Math.max(10, Math.sqrt(Math.pow(mascot.environment.cursor.x - mascot.anchor.x, 2) + Math.pow(mascot.environment.cursor.y - mascot.anchor.y, 2)) * 0.02)}"/>
						<!-- Intercepted cursor during fall → grab -->
						<Action Type="Sequence"
							Condition="${(function(){ var dx = mascot.environment.cursor.x - mascot.anchor.x; var dy = mascot.environment.cursor.y - mascot.anchor.y; return Math.sqrt(dx*dx + dy*dy) &lt;= Math.round(100 * mascot.currentScale); })()}">
							<ActionReference Name="Dragged" />
						</Action>
					</Action>
				</Action>
			</Action>
    	</Action>'''


JUMPTOCURSOR_BEHAVIOR = '''		
        <Behavior Name="JumpToCursor" Frequency="10" Toggleable="true"
			Condition="#{
				var dx = mascot.environment.cursor.x - mascot.anchor.x;
				var dy = mascot.anchor.y - mascot.environment.cursor.y;
				var dist = Math.sqrt(dx*dx + dy*dy);
				dist &lt;= 400
			}">
			<NextBehaviorList Add="false">
				<BehaviorReference Name="Fall" Frequency="1" />
			</NextBehaviorList>
		</Behavior>'''


def patch_fix_jumptocursor(actions_content, behaviors_content, mascot_name):
    """
    Add / Fix JumpToCursor:
      - If mascot lacks a Jumping action definition → skip entirely
      - If absent entirely → append before </ActionList>
      - If present but outdated (fingerprint missing) → replace in-place
      - If present and already current → skip
    Also adds the JumpToCursor behavior to behaviors.xml if missing.
    """
    log = []

    if not _has_action_def(actions_content, 'Jumping'):
        log.append(f"  – Skipped {mascot_name}/actions.xml (no Jumping action defined)")
        return actions_content, behaviors_content, log

    # ── actions.xml ──
    start_tag = '<Action Name="JumpToCursor"'
    has_block = start_tag in actions_content
    is_current = _JUMPTOCURSOR_FINGERPRINT in actions_content

    if not has_block:
        actions_content = actions_content.replace(
            '</ActionList>',
            '\n' + NEW_JUMP_TO_CURSOR + '\n\t</ActionList>',
            1
        )
        log.append(f"  ✓ Added JumpToCursor to {mascot_name}/actions.xml")

    elif not is_current:
        pos = 0
        while True:
            idx = actions_content.find(start_tag, pos)
            if idx == -1:
                break
            end_idx = _find_action_end(actions_content, idx)
            if end_idx == -1:
                log.append(f"  WARNING: Could not find closing tag for JumpToCursor in {mascot_name}")
                break
            actions_content = actions_content[:idx] + NEW_JUMP_TO_CURSOR + actions_content[end_idx:]
            pos = idx + len(NEW_JUMP_TO_CURSOR)
        log.append(f"  ✓ Updated JumpToCursor (outdated) in {mascot_name}/actions.xml")

    else:
        log.append(f"  – JumpToCursor already up-to-date in {mascot_name}/actions.xml")

    # ── behaviors.xml ──
    if behaviors_content and not _has_behavior_def(behaviors_content, 'JumpToCursor'):
        behaviors_content = behaviors_content.replace(
            '</BehaviorList>',
            JUMPTOCURSOR_BEHAVIOR + '\n\t</BehaviorList>',
            1
        )
        log.append(f"  ✓ Added JumpToCursor behavior to {mascot_name}/behaviors.xml")
    elif behaviors_content:
        # Check every JumpToCursor behavior entry — stale if self-closing (no NextBehaviorList)
        jtc_beh_stale = False
        search_from = 0
        while True:
            idx = behaviors_content.find('<Behavior Name="JumpToCursor"', search_from)
            if idx == -1:
                break
            end = behaviors_content.find('>', idx)
            if end != -1 and behaviors_content[end - 1] == '/':
                jtc_beh_stale = True
                break
            search_from = end + 1 if end != -1 else idx + 1

        if jtc_beh_stale:
            # Remove all JumpToCursor behavior entries then re-add the fixed one
            temp = behaviors_content
            search_from = 0
            while True:
                idx = temp.find('<Behavior Name="JumpToCursor"', search_from)
                if idx == -1:
                    break
                end = temp.find('>', idx)
                if end == -1:
                    break
                if temp[end - 1] == '/':
                    end_idx = end + 1
                else:
                    close = temp.find('</Behavior>', end)
                    end_idx = close + len('</Behavior>') if close != -1 else end + 1
                # Trim leading whitespace/newline
                start = idx
                while start > 0 and temp[start - 1] in ' \t':
                    start -= 1
                if start > 0 and temp[start - 1] == '\n':
                    start -= 1
                temp = temp[:start] + temp[end_idx:]
            behaviors_content = temp.replace(
                '</BehaviorList>',
                JUMPTOCURSOR_BEHAVIOR + '\n\t</BehaviorList>',
                1
            )
            log.append(f"  ✓ Updated JumpToCursor behavior (added NextBehaviorList) in {mascot_name}/behaviors.xml")
        else:
            log.append(f"  – JumpToCursor behavior already up-to-date in {mascot_name}/behaviors.xml")

    return actions_content, behaviors_content, log


# ─────────────────────────────────────────────
#  PATCH: Add / Fix Scale
# ─────────────────────────────────────────────

SCALE_ACTIONS = '''
		<Action Name="ScaleUp" Type="Embedded"
			Class="com.group_finity.mascot.action.Scale"
			Target="#{mascot.currentScale + 0.1}"
			Speed="0.2"
			Duration="8" />
		<Action Name="ScaleDown" Type="Embedded"
			Class="com.group_finity.mascot.action.Scale"
			Target="#{mascot.currentScale - 0.1}"
			Speed="0.2"
			Duration="8" />'''

SCALE_BEHAVIORS = '''
		<Behavior Name="ScaleUp" Frequency="0" />
		<Behavior Name="ScaleDown" Frequency="0" />'''


def patch_fix_scale(actions_content, behaviors_content, mascot_name):
    """
    Add ScaleUp / ScaleDown actions and behaviors if not already present.
    """
    log = []

    # ── Actions ──
    has_scale_up   = _has_action_def(actions_content, 'ScaleUp')
    has_scale_down = _has_action_def(actions_content, 'ScaleDown')

    if not has_scale_up or not has_scale_down:
        actions_content = actions_content.replace(
            '</ActionList>',
            SCALE_ACTIONS + '\n\t</ActionList>',
            1
        )
        log.append(f"  ✓ Added Scale actions to {mascot_name}/actions.xml")
    else:
        log.append(f"  – Scale actions already present in {mascot_name}/actions.xml")

    # ── Behaviors ──
    has_beh_up   = _has_behavior_def(behaviors_content, 'ScaleUp')
    has_beh_down = _has_behavior_def(behaviors_content, 'ScaleDown')

    if behaviors_content and (not has_beh_up or not has_beh_down):
        behaviors_content = behaviors_content.replace(
            '</BehaviorList>',
            SCALE_BEHAVIORS + '\n\t</BehaviorList>',
            1
        )
        log.append(f"  ✓ Added Scale behaviors to {mascot_name}/behaviors.xml")
    elif behaviors_content:
        log.append(f"  – Scale behaviors already present in {mascot_name}/behaviors.xml")

    return actions_content, behaviors_content, log


# ─────────────────────────────────────────────
#  PATCH: Add Manual Movement
# ─────────────────────────────────────────────

MANUAL_MOVEMENT_ACTIONS = '''
		<!-- Manual Movement -->

		<Action Name="MoveLeft" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="false" />
			<Action Type="Select">
				<!-- If on the floor or IE top, walk -->
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Walk" TargetX="${mascot.anchor.x - 200}" />
				</Action>
				<!-- Otherwise nudge left mid-air -->
				<ActionReference Name="Falling"
					InitialVX="-8"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		<Action Name="MoveRight" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="true" />
			<Action Type="Select">
				<!-- If on the floor or IE top, walk -->
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Walk" TargetX="${mascot.anchor.x + 200}" />
				</Action>
				<!-- Otherwise nudge right mid-air -->
				<ActionReference Name="Falling"
					InitialVX="8"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		<Action Name="RunLeft" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="false" />
			<Action Type="Select">
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Run" TargetX="${mascot.anchor.x - 300}" />
				</Action>
				<ActionReference Name="Falling"
					InitialVX="-12"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		<Action Name="RunRight" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="true" />
			<Action Type="Select">
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Run" TargetX="${mascot.anchor.x + 300}" />
				</Action>
				<ActionReference Name="Falling"
					InitialVX="12"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		<Action Name="DashLeft" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="false" />
			<Action Type="Select">
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Dash" TargetX="${mascot.anchor.x - 400}" />
				</Action>
				<ActionReference Name="Falling"
					InitialVX="-20"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		<Action Name="DashRight" Type="Sequence" Loop="false">
			<ActionReference Name="Look" LookRight="true" />
			<Action Type="Select">
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Dash" TargetX="${mascot.anchor.x + 400}" />
				</Action>
				<ActionReference Name="Falling"
					InitialVX="20"
					InitialVY="-1"
					Duration="3"/>
			</Action>
		</Action>

		

		<Action Name="ManualJump" Type="Sequence" Loop="false">
			<Action Type="Select">
				<!-- Wall cling on right side → jump left away from wall -->
				<Action Type="Sequence"
					Condition="${mascot.lookRight &amp;&amp; (mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor) || mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor))}">
					<ActionReference Name="Look" LookRight="false" />
					<ActionReference Name="Jumping"
						TargetX="${mascot.anchor.x - Math.round(192 * mascot.currentScale)}"
						TargetY="${mascot.anchor.y - Math.round(192 * mascot.currentScale)}"/>
					<Action Type="Sequence">
						<Action Type="Select">
							<Action Type="Sequence"
								Condition="${mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor) || mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor)}">
								<ActionReference Name="GrabWall" 
								Duration="${100 + Math.random() * 100}" />
							</Action>
							<Action Type="Sequence"
								Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
								<ActionReference Name="Stand" 
								Duration="1" />
							</Action>							
							<!-- Still airborne → keep falling with momentum -->
							<ActionReference Name="Falling"
								InitialVX="${mascot.lookRight ? 10 : -10}"
								InitialVY="2"
								Duration="100"/>
						</Action>
					</Action>
				</Action>

				<!-- Wall cling on left side → jump right away from wall -->
				<Action Type="Sequence"
					Condition="${!mascot.lookRight &amp;&amp; (mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor) || mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor))}">
					<ActionReference Name="Look" LookRight="true" />
					<ActionReference Name="Jumping"
						TargetX="${mascot.anchor.x + Math.round(192 * mascot.currentScale)}"
						TargetY="${mascot.anchor.y - Math.round(192 * mascot.currentScale)}" />
					<Action Type="Sequence">
						<Action Type="Select">
							<Action Type="Sequence"
								Condition="${mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor) || mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor)}">
								<ActionReference Name="GrabWall" 
								Duration="${100 + Math.random() * 100}" />
							</Action>
							<Action Type="Sequence"
								Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
								<ActionReference Name="Stand" 
								Duration="1" />
							</Action>							
							<!-- Still airborne → keep falling with momentum -->
							<ActionReference Name="Falling"
								InitialVX="${mascot.lookRight ? 10 : -10}"
								InitialVY="2"
								Duration="100"/>
						</Action>
					</Action>
				</Action>

				<!-- On the floor or IE top → normal jump -->
				<Action Type="Sequence"
					Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
					<ActionReference Name="Jumping"
						TargetX="${mascot.lookRight
							? mascot.anchor.x + Math.round(128 * mascot.currentScale)
							: mascot.anchor.x - Math.round(128 * mascot.currentScale)}"
						TargetY="${mascot.anchor.y - Math.round(192 * mascot.currentScale)}" />
					<Action Type="Sequence">
						<Action Type="Select">
							<Action Type="Sequence"
								Condition="${mascot.environment.activeIE.leftBorder.isOn(mascot.anchor) || mascot.environment.activeIE.rightBorder.isOn(mascot.anchor) || mascot.environment.workArea.leftBorder.isOn(mascot.anchor) || mascot.environment.workArea.rightBorder.isOn(mascot.anchor)}">
								<ActionReference Name="GrabWall" 
								Duration="${100 + Math.random() * 100}" />
							</Action>
							<Action Type="Sequence"
								Condition="${mascot.environment.floor.isOn(mascot.anchor) || mascot.environment.activeIE.topBorder.isOn(mascot.anchor)}">
								<ActionReference Name="Stand" 
								Duration="1" />
							</Action>							
							<!-- Still airborne → keep falling with momentum -->
							<ActionReference Name="Falling"
								InitialVX="${mascot.lookRight ? 10 : -10}"
								InitialVY="2"
								Duration="100"/>
						</Action>
					</Action>
				</Action>

				<!-- Not on any surface → fall -->
				<ActionReference Name="Falling"
					InitialVX="${mascot.lookRight ? 10 : -10}"
					InitialVY="2"
					Duration="100"/>
			</Action>
		</Action>'''

# Proximity condition snippets — dx >= 0 means threat is to the right (flee left)
#                                dx <= 0 means threat is to the left  (flee right)
_PROX_FLEE_LEFT = (
    '#{(function(){\n'
    '\t\t\tvar others = mascot.manager.getMascotList();\n'
    '\t\t\tfor(var i=0;i &lt; others.size();i++){\n'
    '\t\t\t\tvar o=others.get(i); if(o===mascot) continue;\n'
    '\t\t\t\tvar dx=o.anchor.x-mascot.anchor.x;\n'
    '\t\t\t\tvar dy=o.anchor.y-mascot.anchor.y;\n'
    '\t\t\t\tif(Math.sqrt(dx*dx+dy*dy) &lt;=(32 * mascot.currentScale) &amp;&amp; dx &gt;=0) return true;\n'
    '\t\t\t} return false;\n'
    '\t\t\t})()\n'
    '\t\t\t}'
)
_PROX_FLEE_RIGHT = (
    '#{(function(){\n'
    '\t\t\tvar others = mascot.manager.getMascotList();\n'
    '\t\t\tfor(var i=0;i &lt; others.size();i++){\n'
    '\t\t\t\tvar o=others.get(i); if(o===mascot) continue;\n'
    '\t\t\t\tvar dx=o.anchor.x-mascot.anchor.x;\n'
    '\t\t\t\tvar dy=o.anchor.y-mascot.anchor.y;\n'
    '\t\t\t\tif(Math.sqrt(dx*dx+dy*dy) &lt;=(32 * mascot.currentScale) &amp;&amp; dx &lt;=0) return true;\n'
    '\t\t\t} return false;\n'
    '\t\t\t})()\n'
    '\t\t\t}'
)


def _make_manual_movement_behaviors(proximity=False):
    """
    Build the MANUAL_MOVEMENT_BEHAVIORS string.
    If proximity=True, Move/Run/DashLeft get flee-left condition and
    Move/Run/DashRight get flee-right condition, each at Frequency=5000.
    If proximity=False, all stay at Frequency=0 with no condition (manual-only).
    """
    def beh(name, freq, condition=None):
        parts = [f'\t\t<Behavior Name="{name}" Frequency="{freq}"']
        if condition:
            parts[0] += f' Toggleable="true"\n\t\t\tCondition="{condition}"'
        parts[0] += '>'
        parts.append('\t\t\t<NextBehaviorList Add="false">')
        parts.append('\t\t\t\t<BehaviorReference Name="Fall" Frequency="1" />')
        parts.append('\t\t\t</NextBehaviorList>')
        parts.append('\t\t</Behavior>')
        return '\n'.join(parts)

    if proximity:
        left_freq, right_freq = 5000, 5000
        left_cond, right_cond = _PROX_FLEE_LEFT, _PROX_FLEE_RIGHT
    else:
        left_freq = right_freq = 0
        left_cond = right_cond = None

    lines = [
        beh('MoveLeft',  left_freq,  left_cond),
        beh('MoveRight', right_freq, right_cond),
        beh('RunLeft',   left_freq,  left_cond),
        beh('RunRight',  right_freq, right_cond),
        beh('DashLeft',  left_freq,  left_cond),
        beh('DashRight', right_freq, right_cond),
        '\t\t<Behavior Name="ManualJump" Frequency="10" >\n'
        '\t\t\t<NextBehaviorList Add="false">\n'
        '\t\t\t\t<BehaviorReference Name="Fall" Frequency="1" />\n'
        '\t\t\t</NextBehaviorList>\n'
        '\t\t</Behavior>',
    ]
    return '\n' + '\n'.join(lines)


def patch_add_manual_movement(actions_content, behaviors_content, mascot_name,
                              proximity=False):
    """
    Add manual movement actions/behaviors if:
      - actions.xml has Walk, Run, Dash, and Jumping action definitions
      - they are not already present
    Remove them if they exist but the mascot lacks the required action definitions.

    proximity: if True, Left behaviors get a flee-left proximity condition and
               Right behaviors get a flee-right condition, each at Frequency=5000.
               If False, all behaviors are Frequency=0 (manual-only).
    """
    log = []
    required = ['Walk', 'Run', 'Dash', 'Jumping']
    qualifies = all(_has_action_def(actions_content, r) for r in required)

    has_move_action = _has_action_def(actions_content, 'MoveLeft')
    has_move_behavior = _has_behavior_def(behaviors_content, 'MoveLeft')

    # ── Actions ──
    if qualifies and not has_move_action:
        actions_content = actions_content.replace(
            '</ActionList>',
            MANUAL_MOVEMENT_ACTIONS + '\n\t</ActionList>',
            1
        )
        log.append(f"  ✓ Added manual movement actions to {mascot_name}/actions.xml")
    elif not qualifies and has_move_action:
        actions_content, removed = _remove_manual_movement_actions(actions_content)
        if removed:
            log.append(f"  ✗ Removed manual movement actions from {mascot_name}/actions.xml (missing Walk/Run/Dash/Jumping)")
    elif qualifies and has_move_action:
        log.append(f"  – Manual movement already present in {mascot_name}/actions.xml")
    else:
        log.append(f"  – Skipped {mascot_name}/actions.xml (missing required action definitions)")

    # ── Behaviors ──
    behaviors_str = _make_manual_movement_behaviors(proximity=proximity)
    if qualifies and not has_move_behavior:
        behaviors_content = behaviors_content.replace(
            '</BehaviorList>',
            behaviors_str + '\n\t</BehaviorList>',
            1
        )
        prox_note = " (with proximity conditions)" if proximity else ""
        log.append(f"  ✓ Added manual movement behaviors to {mascot_name}/behaviors.xml{prox_note}")
    elif not qualifies and has_move_behavior:
        behaviors_content, removed = _remove_manual_movement_behaviors(behaviors_content)
        if removed:
            log.append(f"  ✗ Removed manual movement behaviors from {mascot_name}/behaviors.xml")
    elif qualifies and has_move_behavior:
        log.append(f"  – Manual movement behaviors already present in {mascot_name}/behaviors.xml")

    return actions_content, behaviors_content, log


def _remove_manual_movement_actions(content):
    marker = '<!-- Manual Movement -->'
    idx = content.find(marker)
    if idx == -1:
        idx = content.find('<Action Name="MoveLeft"')
        if idx == -1:
            return content, False
    start = idx
    while start > 0 and content[start - 1] in ' \t\n':
        start -= 1
    start += 1

    mj_start = content.find('<Action Name="ManualJump"', start)
    if mj_start == -1:
        return content, False

    end_idx = _find_action_end(content, mj_start)
    if end_idx == -1:
        return content, False

    return content[:start] + content[end_idx:], True


def _remove_manual_movement_behaviors(content):
    names = ['MoveLeft', 'MoveRight', 'RunLeft', 'RunRight', 'DashLeft', 'DashRight', 'ManualJump']
    changed = False
    for name in names:
        pattern = f'<Behavior Name="{name}"'
        idx = content.find(pattern)
        if idx == -1:
            continue
        end = content.find('>', idx)
        if end == -1:
            continue
        if content[end - 1] == '/':
            end_idx = end + 1
        else:
            close = content.find('</Behavior>', end)
            if close == -1:
                continue
            end_idx = close + len('</Behavior>')
        start = idx
        while start > 0 and content[start - 1] in ' \t':
            start -= 1
        if start > 0 and content[start - 1] == '\n':
            start -= 1
        content = content[:start] + content[end_idx:]
        changed = True
    return content, changed


# ─────────────────────────────────────────────
#  PATCH: Custom XML
# ─────────────────────────────────────────────

def _extract_xml_name(xml_snippet):
    """Pull the Name=... attribute from the first tag in a snippet."""
    m = re.search(r'<(?:Action|Behavior)\s[^>]*Name="([^"]+)"', xml_snippet)
    return m.group(1) if m else None


def _replace_or_add_action(content, name, new_xml):
    """Replace an existing Action block by name, or append before </ActionList>."""
    start_tag = f'<Action Name="{name}"'
    idx = content.find(start_tag)
    if idx != -1:
        end_idx = _find_action_end(content, idx)
        if end_idx != -1:
            return content[:idx] + new_xml.strip() + content[end_idx:], "replaced"
    content = content.replace('</ActionList>', '\n\t\t' + new_xml.strip() + '\n\t</ActionList>', 1)
    return content, "added"


def _replace_or_add_behavior(content, name, new_xml):
    """Replace an existing Behavior block by name, or append before </BehaviorList>."""
    pattern = f'<Behavior Name="{name}"'
    idx = content.find(pattern)
    if idx != -1:
        end = content.find('>', idx)
        if end != -1:
            if content[end - 1] == '/':
                end_idx = end + 1
            else:
                close = content.find('</Behavior>', end)
                end_idx = close + len('</Behavior>') if close != -1 else end + 1
            start = idx
            while start > 0 and content[start - 1] in ' \t':
                start -= 1
            return content[:start] + new_xml.strip() + content[end_idx:], "replaced"
    content = content.replace('</BehaviorList>', '\n\t\t' + new_xml.strip() + '\n\t</BehaviorList>', 1)
    return content, "added"


def make_custom_patch(action_xml, behavior_xml):
    """
    Returns a patch function that adds/replaces the given action and/or behavior XML
    in every mascot. Either may be an empty string to skip that file.
    """
    action_name   = _extract_xml_name(action_xml)   if action_xml.strip()   else None
    behavior_name = _extract_xml_name(behavior_xml) if behavior_xml.strip() else None

    def _patch(actions_content, behaviors_content, mascot_name):
        log = []
        if action_name and action_xml.strip():
            actions_content, verb = _replace_or_add_action(
                actions_content, action_name, action_xml.strip())
            log.append(f"  \u2713 Custom: {verb} action '{action_name}' in {mascot_name}/actions.xml")
        if behavior_name and behavior_xml.strip() and behaviors_content:
            behaviors_content, verb = _replace_or_add_behavior(
                behaviors_content, behavior_name, behavior_xml.strip())
            log.append(f"  \u2713 Custom: {verb} behavior '{behavior_name}' in {mascot_name}/behaviors.xml")
        return actions_content, behaviors_content, log

    return _patch


# ─────────────────────────────────────────────
#  PATCH: Add Personal Space
# ─────────────────────────────────────────────

PERSONAL_SPACE_FALLBACK_ANIMATION = '''\t\t\t<Animation>
\t\t\t\t<Pose Image="/shime1.png" ImageAnchor="64,128" Velocity="-4,0" Duration="6" />
\t\t\t</Animation>'''


def _extract_walk_animation(actions_content):
    """
    Pull the first <Animation>...</Animation> block from the Walk action.
    Returns the indented XML string, or None if not found.
    """
    walk_start = actions_content.find('<Action Name="Walk"')
    if walk_start == -1:
        return None
    walk_end = _find_action_end(actions_content, walk_start)
    if walk_end == -1:
        return None
    walk_block = actions_content[walk_start:walk_end]

    anim_start = walk_block.find('<Animation')
    if anim_start == -1:
        return None
    anim_end = walk_block.find('</Animation>', anim_start)
    if anim_end == -1:
        return None
    return '\t\t\t' + walk_block[anim_start:anim_end + len('</Animation>')]



# ─────────────────────────────────────────────
#  PATCH REGISTRY
#  Each entry: (id, label, description, function)
#  function signature: (actions_str, behaviors_str, mascot_name) -> (actions_str, behaviors_str, log_lines)
# ─────────────────────────────────────────────

PATCHES = [
    {
        "id": "fix_jumptocursor",
        "label": "Add / Fix JumpToCursor",
        "description": (
            "Adds JumpToCursor to any actions.xml that is missing it. "
            "If it already exists but is outdated, replaces it with the current version "
            "(clings to walls, stands on IE/floor, re-jumps if cursor is above, grabs when reached). "
            "Skips mascots that are already up-to-date."
        ),
        "targets": "actions",
        "fn": patch_fix_jumptocursor,
    },
    {
        "id": "add_manual_movement",
        "label": "Add / Fix Manual Movement",
        "description": (
            "Adds manual movement actions (MoveLeft/Right, RunLeft/Right, DashLeft/Right, ManualJump) "
            "to mascots that have Walk, Run, Dash, and Jumping. "
            "Also removes manual movement from mascots that are missing those base actions. "
            "Enable 'Personal Space' below to also trigger movement automatically when another "
            "shimeji is within ~32px (scale-dependent)."
        ),
        "targets": "both",
        "fn": patch_add_manual_movement,
        "sub_options": [
            {
                "id": "manual_movement_proximity",
                "kwarg": "proximity",
                "label": "  ↳ Personal Space (proximity flee)",
                "description": (
                    "Left/Right behaviors fire at Frequency=5000 with a condition that checks "
                    "mascot.manager.getMascotList() for any other shimeji within 32×scale px. "
                    "Requires getMascotList() in Manager.java."
                ),
            }
        ],
    },
    {
        "id": "fix_scale",
        "label": "Add / Fix Scale",
        "description": (
            "Adds ScaleUp and ScaleDown actions to actions.xml and their matching behaviors "
            "to behaviors.xml for any mascot that doesn't already have them."
        ),
        "targets": "both",
        "fn": patch_fix_scale,
    },
]


# ─────────────────────────────────────────────
#  CORE ENGINE
# ─────────────────────────────────────────────

def _parse_exclusions(raw):
    """Parse a comma-separated exclusion string into a list of lowercase substrings."""
    return [s.strip().lower() for s in raw.split(',') if s.strip()]


def _is_excluded(name, exclusions):
    """Return True if the mascot name contains any exclusion substring (case-insensitive)."""
    name_lower = name.lower()
    return any(ex in name_lower for ex in exclusions)


def find_mascot_dirs(root_dir, exclusions=None):
    """Walk root_dir and find all subdirectories that contain a conf/actions.xml."""
    exclusions = exclusions or []
    mascots = []
    for dirpath, dirnames, filenames in os.walk(root_dir):
        if os.path.basename(dirpath) == 'conf':
            actions = os.path.join(dirpath, 'actions.xml')
            behaviors = os.path.join(dirpath, 'behaviors.xml')
            if os.path.exists(actions):
                mascot_name = os.path.basename(os.path.dirname(dirpath))
                if not _is_excluded(mascot_name, exclusions):
                    mascots.append({
                        "name": mascot_name,
                        "actions_path": actions,
                        "behaviors_path": behaviors if os.path.exists(behaviors) else None,
                    })
    return sorted(mascots, key=lambda m: m["name"].lower())


def run_patches(root_dir, selected_patch_ids, progress_cb=None, log_cb=None,
                extra_patches=None, exclusions=None, patch_options=None):
    """
    Run the selected patches across all mascots.
    exclusions: list of lowercase substrings — mascots whose names contain any are skipped.
    patch_options: dict of patch_id -> dict of extra kwargs forwarded to the patch fn.
    """
    patch_options = patch_options or {}
    mascots = find_mascot_dirs(root_dir, exclusions=exclusions)
    if not mascots:
        if log_cb:
            log_cb("No mascot directories found. Make sure you selected the folder containing mascot subfolders.")
        return

    all_patches = PATCHES + (extra_patches or [])
    selected = [p for p in all_patches if p["id"] in selected_patch_ids]
    if not selected:
        if log_cb:
            log_cb("No patches selected.")
        return

    total = len(mascots)
    for i, mascot in enumerate(mascots):
        if log_cb:
            log_cb(f"\n── {mascot['name']} ──")

        try:
            with open(mascot["actions_path"], 'r', encoding='utf-8') as f:
                actions_content = f.read()
        except Exception as e:
            if log_cb:
                log_cb(f"  ERROR reading actions.xml: {e}")
            continue

        behaviors_content = ""
        if mascot["behaviors_path"]:
            try:
                with open(mascot["behaviors_path"], 'r', encoding='utf-8') as f:
                    behaviors_content = f.read()
            except Exception as e:
                if log_cb:
                    log_cb(f"  ERROR reading behaviors.xml: {e}")

        for patch in selected:
            try:
                kwargs = patch_options.get(patch["id"], {})
                actions_content, behaviors_content, log_lines = patch["fn"](
                    actions_content, behaviors_content, mascot["name"], **kwargs
                )
                if log_cb:
                    for line in log_lines:
                        log_cb(line)
            except Exception as e:
                if log_cb:
                    log_cb(f"  ERROR in patch '{patch['label']}': {e}")

        try:
            with open(mascot["actions_path"], 'w', encoding='utf-8') as f:
                f.write(actions_content)
        except Exception as e:
            if log_cb:
                log_cb(f"  ERROR writing actions.xml: {e}")

        if mascot["behaviors_path"] and behaviors_content:
            try:
                with open(mascot["behaviors_path"], 'w', encoding='utf-8') as f:
                    f.write(behaviors_content)
            except Exception as e:
                if log_cb:
                    log_cb(f"  ERROR writing behaviors.xml: {e}")

        if progress_cb:
            progress_cb(int((i + 1) / total * 100))

    if log_cb:
        log_cb("\n✅ Done!")


# ─────────────────────────────────────────────
#  GUI
# ─────────────────────────────────────────────

DARK_BG       = "#1a1a2e"
PANEL_BG      = "#16213e"
CARD_BG       = "#0f3460"
CUSTOM_BG     = "#12294a"
ACCENT        = "#e94560"
ACCENT_HOVER  = "#ff6b81"
TEXT          = "#eaeaea"
TEXT_DIM      = "#8888aa"
TEXT_CHECK    = "#ffffff"
SUCCESS       = "#4ecca3"
FONT_MAIN     = ("Consolas", 10)
FONT_TITLE    = ("Consolas", 18, "bold")
FONT_SUBTITLE = ("Consolas", 9)
FONT_BTN      = ("Consolas", 10, "bold")
FONT_LABEL    = ("Consolas", 10)
FONT_LOG      = ("Consolas", 9)
FONT_XML      = ("Consolas", 9)


class MascotEditorApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Mascot XML Batch Editor")
        self.configure(bg=DARK_BG)
        self.resizable(True, True)
        self.minsize(800, 620)

        self._folder = tk.StringVar(value="")
        self._patch_vars = {p["id"]: tk.BooleanVar(value=False) for p in PATCHES}
        # Sub-option vars: patch_id -> {sub_id -> BooleanVar}
        self._sub_option_vars = {}
        for p in PATCHES:
            for sub in p.get("sub_options", []):
                self._sub_option_vars.setdefault(p["id"], {})[sub["id"]] = tk.BooleanVar(value=False)
        # Custom patch
        self._custom_enabled = tk.BooleanVar(value=False)
        self._running = False

        self._build_ui()
        self._center_window(980, 720)

    def _center_window(self, w, h):
        self.update_idletasks()
        sw = self.winfo_screenwidth()
        sh = self.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        self.geometry(f"{w}x{h}+{x}+{y}")

    # ── Layout ───────────────────────────────

    def _build_ui(self):
        # Title bar
        title_frame = tk.Frame(self, bg=PANEL_BG, pady=14)
        title_frame.pack(fill="x")
        tk.Label(title_frame, text="MASCOT XML BATCH EDITOR",
                 font=FONT_TITLE, fg=ACCENT, bg=PANEL_BG).pack()
        tk.Label(title_frame, text="batch-patch your shimeji actions & behaviors",
                 font=FONT_SUBTITLE, fg=TEXT_DIM, bg=PANEL_BG).pack()

        tk.Frame(self, bg=ACCENT, height=2).pack(fill="x")

        # Body
        body = tk.Frame(self, bg=DARK_BG)
        body.pack(fill="both", expand=True, padx=20, pady=14)

        # ── Left column: scrollable panel ──
        left_outer = tk.Frame(body, bg=DARK_BG, width=340)
        left_outer.pack(side="left", fill="y", padx=(0, 14))
        left_outer.pack_propagate(False)

        # Canvas + scrollbar for left panel
        self._left_canvas = tk.Canvas(left_outer, bg=DARK_BG, bd=0,
                                      highlightthickness=0)
        left_scroll = tk.Scrollbar(left_outer, orient="vertical",
                                   command=self._left_canvas.yview)
        self._left_canvas.configure(yscrollcommand=left_scroll.set)

        left_scroll.pack(side="right", fill="y")
        self._left_canvas.pack(side="left", fill="both", expand=True)

        self._left_inner = tk.Frame(self._left_canvas, bg=DARK_BG)
        self._left_canvas_window = self._left_canvas.create_window(
            (0, 0), window=self._left_inner, anchor="nw")

        self._left_inner.bind("<Configure>", self._on_left_configure)
        self._left_canvas.bind("<Configure>", self._on_canvas_resize)
        self._left_canvas.bind("<Enter>", lambda e: self._left_canvas.bind_all(
            "<MouseWheel>", self._on_mousewheel))
        self._left_canvas.bind("<Leave>", lambda e: self._left_canvas.unbind_all(
            "<MouseWheel>"))

        left = self._left_inner
        self._build_folder_section(left)
        self._build_patches_section(left)
        self._build_custom_section(left)
        self._build_run_section(left)

        # ── Right column: log ──
        right = tk.Frame(body, bg=DARK_BG)
        right.pack(side="left", fill="both", expand=True)
        self._build_log_section(right)

    def _on_left_configure(self, event):
        self._left_canvas.configure(
            scrollregion=self._left_canvas.bbox("all"))

    def _on_canvas_resize(self, event):
        self._left_canvas.itemconfig(
            self._left_canvas_window, width=event.width)

    def _on_mousewheel(self, event):
        self._left_canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    # ── Sections ─────────────────────────────

    def _build_folder_section(self, parent):
        self._section_label(parent, "📁  TARGET FOLDER")

        row = tk.Frame(parent, bg=DARK_BG)
        row.pack(fill="x", pady=(4, 4))

        entry = tk.Entry(row, textvariable=self._folder,
                         font=FONT_MAIN, bg=CARD_BG, fg=TEXT,
                         insertbackground=TEXT, relief="flat",
                         bd=0, highlightthickness=1,
                         highlightbackground=ACCENT, highlightcolor=ACCENT)
        entry.pack(side="left", fill="x", expand=True, ipady=6, padx=(0, 6))
        self._btn(row, "Browse", self._browse_folder, small=True).pack(side="left")

        self._mascot_count_label = tk.Label(parent, text="", font=FONT_SUBTITLE,
                                             fg=TEXT_DIM, bg=DARK_BG)
        self._mascot_count_label.pack(anchor="w", pady=(0, 4))
        self._folder.trace_add("write", lambda *_: self._refresh_mascot_count())

        # Exclusion filter
        self._section_label(parent, "🚫  EXCLUDE (optional)")
        tk.Label(parent, text="Comma-separated substrings — any mascot folder whose name\n"
                              "contains a match will be skipped (case-insensitive).",
                 font=FONT_SUBTITLE, fg=TEXT_DIM, bg=DARK_BG,
                 wraplength=300, justify="left").pack(anchor="w", pady=(0, 3))
        self._exclude_var = tk.StringVar(value="")
        exclude_entry = tk.Entry(parent, textvariable=self._exclude_var,
                                 font=FONT_MAIN, bg=CARD_BG, fg=TEXT,
                                 insertbackground=TEXT, relief="flat", bd=0,
                                 highlightthickness=1,
                                 highlightbackground=ACCENT, highlightcolor=ACCENT)
        exclude_entry.pack(fill="x", ipady=6, pady=(0, 2))
        self._exclude_var.trace_add("write", lambda *_: self._refresh_mascot_count())
        tk.Label(parent, text='e.g.  egg, campfire, grass', font=FONT_SUBTITLE,
                 fg=TEXT_DIM, bg=DARK_BG).pack(anchor="w", pady=(0, 6))

    def _build_patches_section(self, parent):
        self._section_label(parent, "🔧  PATCHES")
        for patch in PATCHES:
            self._build_patch_card(parent, patch)

    def _build_patch_card(self, parent, patch):
        card = tk.Frame(parent, bg=CARD_BG, padx=10, pady=8,
                        highlightthickness=1, highlightbackground="#1e3a5f")
        card.pack(fill="x", pady=3)

        top = tk.Frame(card, bg=CARD_BG)
        top.pack(fill="x")

        var = self._patch_vars[patch["id"]]
        cb = tk.Checkbutton(top, variable=var, bg=CARD_BG, fg=TEXT_CHECK,
                            activebackground=CARD_BG, activeforeground=TEXT_CHECK,
                            selectcolor=ACCENT, relief="flat", bd=0,
                            font=FONT_LABEL, text=patch["label"],
                            anchor="w", cursor="hand2")
        cb.pack(side="left", fill="x", expand=True)

        desc = tk.Label(card, text=patch["description"], font=FONT_SUBTITLE,
                        fg=TEXT_DIM, bg=CARD_BG, wraplength=290,
                        justify="left", anchor="w")
        desc.pack(fill="x", pady=(3, 0))

        # Sub-options (indented checkboxes under this card)
        for sub in patch.get("sub_options", []):
            sub_var = self._sub_option_vars[patch["id"]][sub["id"]]
            sub_cb = tk.Checkbutton(card, variable=sub_var, bg=CARD_BG, fg=TEXT_DIM,
                                    activebackground=CARD_BG, activeforeground=TEXT_CHECK,
                                    selectcolor=ACCENT, relief="flat", bd=0,
                                    font=FONT_SUBTITLE, text=sub["label"],
                                    anchor="w", cursor="hand2")
            sub_cb.pack(fill="x", pady=(4, 0))
            sub_desc = tk.Label(card, text=sub["description"], font=FONT_SUBTITLE,
                                fg=TEXT_DIM, bg=CARD_BG, wraplength=270,
                                justify="left", anchor="w")
            sub_desc.pack(fill="x", padx=(16, 0))

    def _build_custom_section(self, parent):
        self._section_label(parent, "✏️  CUSTOM PATCH")

        # Card container
        card = tk.Frame(parent, bg=CUSTOM_BG, padx=10, pady=8,
                        highlightthickness=1, highlightbackground="#1e3a5f")
        card.pack(fill="x", pady=3)

        # Checkbox header
        cb = tk.Checkbutton(card, variable=self._custom_enabled,
                            bg=CUSTOM_BG, fg=TEXT_CHECK,
                            activebackground=CUSTOM_BG, activeforeground=TEXT_CHECK,
                            selectcolor=ACCENT, relief="flat", bd=0,
                            font=FONT_LABEL, text="Custom XML",
                            anchor="w", cursor="hand2",
                            command=self._on_custom_toggle)
        cb.pack(fill="x")

        tk.Label(card, text="Paste action and/or behavior XML. Leave either blank to skip that file.\n"
                            "Existing entries with the same Name will be replaced.",
                 font=FONT_SUBTITLE, fg=TEXT_DIM, bg=CUSTOM_BG,
                 wraplength=290, justify="left").pack(fill="x", pady=(2, 6))

        # Action XML box
        tk.Label(card, text="Action XML  (actions.xml)", font=("Consolas", 8, "bold"),
                 fg=ACCENT, bg=CUSTOM_BG, anchor="w").pack(fill="x")
        self._custom_action_box = tk.Text(
            card, font=FONT_XML, bg=PANEL_BG, fg=TEXT,
            insertbackground=TEXT, relief="flat", bd=0,
            highlightthickness=1, highlightbackground="#1e3a5f",
            height=6, wrap="none", undo=True)
        self._custom_action_box.pack(fill="x", pady=(2, 6))

        # Behavior XML box
        tk.Label(card, text="Behavior XML  (behaviors.xml)", font=("Consolas", 8, "bold"),
                 fg=ACCENT, bg=CUSTOM_BG, anchor="w").pack(fill="x")
        self._custom_behavior_box = tk.Text(
            card, font=FONT_XML, bg=PANEL_BG, fg=TEXT,
            insertbackground=TEXT, relief="flat", bd=0,
            highlightthickness=1, highlightbackground="#1e3a5f",
            height=3, wrap="none", undo=True)
        self._custom_behavior_box.pack(fill="x", pady=(2, 0))

        # Name detection label
        self._custom_name_label = tk.Label(card, text="", font=FONT_SUBTITLE,
                                           fg=TEXT_DIM, bg=CUSTOM_BG, anchor="w")
        self._custom_name_label.pack(fill="x", pady=(4, 0))

        # Wire up live name detection
        self._custom_action_box.bind("<KeyRelease>", lambda e: self._refresh_custom_names())
        self._custom_behavior_box.bind("<KeyRelease>", lambda e: self._refresh_custom_names())

        # Start greyed out if unchecked
        self._on_custom_toggle()

    def _on_custom_toggle(self):
        state = "normal" if self._custom_enabled.get() else "disabled"
        self._custom_action_box.config(state=state)
        self._custom_behavior_box.config(state=state)

    def _refresh_custom_names(self):
        a_xml = self._custom_action_box.get("1.0", "end").strip()
        b_xml = self._custom_behavior_box.get("1.0", "end").strip()
        a_name = _extract_xml_name(a_xml) if a_xml else None
        b_name = _extract_xml_name(b_xml) if b_xml else None
        parts = []
        if a_name:
            parts.append(f"action: '{a_name}'")
        if b_name:
            parts.append(f"behavior: '{b_name}'")
        if parts:
            self._custom_name_label.config(
                text="Detected → " + "  |  ".join(parts), fg=SUCCESS)
        else:
            self._custom_name_label.config(text="", fg=TEXT_DIM)

    def _build_run_section(self, parent):
        tk.Frame(parent, bg=DARK_BG, height=10).pack()

        btn_row = tk.Frame(parent, bg=DARK_BG)
        btn_row.pack(fill="x")

        self._run_btn = self._btn(btn_row, "▶  RUN PATCHES", self._run, accent=True)
        self._run_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))
        self._btn(btn_row, "Clear Log", self._clear_log, small=True).pack(side="left")

        self._progress = ttk.Progressbar(parent, mode="determinate", maximum=100)
        self._progress.pack(fill="x", pady=(10, 0))

        style = ttk.Style()
        style.theme_use("default")
        style.configure("TProgressbar", troughcolor=PANEL_BG,
                        background=ACCENT, thickness=6)

    def _build_log_section(self, parent):
        self._section_label(parent, "📋  LOG")
        self._log = scrolledtext.ScrolledText(
            parent, font=FONT_LOG, bg=PANEL_BG, fg=TEXT,
            insertbackground=TEXT, relief="flat", bd=0,
            state="disabled", wrap="word",
            highlightthickness=1, highlightbackground=ACCENT
        )
        self._log.pack(fill="both", expand=True)

        self._log.tag_config("ok",     foreground=SUCCESS)
        self._log.tag_config("err",    foreground=ACCENT)
        self._log.tag_config("skip",   foreground=TEXT_DIM)
        self._log.tag_config("header", foreground="#ffd700")
        self._log.tag_config("done",   foreground=SUCCESS, font=("Consolas", 10, "bold"))

    # ── Helpers ──────────────────────────────

    def _section_label(self, parent, text):
        tk.Label(parent, text=text, font=("Consolas", 9, "bold"),
                 fg=ACCENT, bg=DARK_BG, anchor="w").pack(fill="x", pady=(12, 2))

    def _btn(self, parent, text, command, accent=False, small=False):
        bg = ACCENT if accent else CARD_BG
        font = FONT_BTN if not small else ("Consolas", 9, "bold")
        pady = 8 if not small else 5
        b = tk.Button(parent, text=text, command=command,
                      font=font, bg=bg, fg="#ffffff",
                      activebackground=ACCENT_HOVER, activeforeground="#ffffff",
                      relief="flat", bd=0, cursor="hand2",
                      padx=12, pady=pady)
        if accent:
            b.bind("<Enter>", lambda e: b.configure(bg=ACCENT_HOVER))
            b.bind("<Leave>", lambda e: b.configure(bg=ACCENT))
        return b

    def _browse_folder(self):
        folder = filedialog.askdirectory(title="Select mascot root folder")
        if folder:
            self._folder.set(folder)

    def _refresh_mascot_count(self):
        folder = self._folder.get()
        if folder and os.path.isdir(folder):
            exclusions = _parse_exclusions(getattr(self, '_exclude_var', tk.StringVar()).get())
            count = len(find_mascot_dirs(folder, exclusions=exclusions))
            self._mascot_count_label.config(
                text=f"{count} mascot(s) found",
                fg=SUCCESS if count > 0 else ACCENT)
        else:
            self._mascot_count_label.config(text="")

    def _clear_log(self):
        self._log.config(state="normal")
        self._log.delete("1.0", "end")
        self._log.config(state="disabled")

    def _log_line(self, line):
        self._log.config(state="normal")
        if "✓" in line or line.startswith("  ✓"):
            tag = "ok"
        elif "✗" in line or "ERROR" in line or "WARNING" in line:
            tag = "err"
        elif line.startswith("  –"):
            tag = "skip"
        elif line.startswith("──") or line.startswith("\n──"):
            tag = "header"
        elif "✅" in line:
            tag = "done"
        else:
            tag = None
        self._log.insert("end", line + "\n", tag)
        self._log.see("end")
        self._log.config(state="disabled")

    def _set_progress(self, pct):
        self._progress["value"] = pct
        self.update_idletasks()

    # ── Run ──────────────────────────────────

    def _run(self):
        if self._running:
            return

        folder = self._folder.get().strip()
        if not folder or not os.path.isdir(folder):
            messagebox.showerror("No folder", "Please select a valid mascot root folder first.")
            return

        # Collect standard selected patches
        selected = [pid for pid, var in self._patch_vars.items() if var.get()]

        # Build custom patch if enabled
        extra_patches = []
        if self._custom_enabled.get():
            a_xml = self._custom_action_box.get("1.0", "end").strip()
            b_xml = self._custom_behavior_box.get("1.0", "end").strip()
            if not a_xml and not b_xml:
                messagebox.showwarning("Custom patch empty",
                    "The Custom XML patch is checked but both text boxes are empty. "
                    "Please paste some XML or uncheck it.")
                return
            a_name = _extract_xml_name(a_xml) if a_xml else None
            b_name = _extract_xml_name(b_xml) if b_xml else None
            if a_xml and not a_name:
                messagebox.showerror("Custom patch error",
                    "Could not detect a Name attribute in the Action XML.\n"
                    "Make sure it starts with a valid <Action Name=\"...\"> tag.")
                return
            if b_xml and not b_name:
                messagebox.showerror("Custom patch error",
                    "Could not detect a Name attribute in the Behavior XML.\n"
                    "Make sure it starts with a valid <Behavior Name=\"...\"> tag.")
                return
            extra_patches.append({
                "id": "_custom",
                "label": "Custom XML",
                "fn": make_custom_patch(a_xml, b_xml),
            })
            selected.append("_custom")

        if not selected:
            messagebox.showwarning("No patches", "Please select at least one patch to run.")
            return

        self._running = True
        self._run_btn.config(state="disabled", text="Running…")
        self._progress["value"] = 0

        exclusions = _parse_exclusions(self._exclude_var.get())

        # Build patch_options: kwargs to forward to patch functions
        patch_options = {}
        for patch in PATCHES:
            for sub in patch.get("sub_options", []):
                if self._sub_option_vars.get(patch["id"], {}).get(sub["id"], tk.BooleanVar()).get():
                    kwarg = sub.get("kwarg", sub["id"])
                    patch_options.setdefault(patch["id"], {})[kwarg] = True

        def worker():
            run_patches(
                folder, selected,
                progress_cb=lambda pct: self.after(0, self._set_progress, pct),
                log_cb=lambda line: self.after(0, self._log_line, line),
                extra_patches=extra_patches,
                exclusions=exclusions,
                patch_options=patch_options,
            )
            self.after(0, self._on_done)

        threading.Thread(target=worker, daemon=True).start()

    def _on_done(self):
        self._running = False
        self._run_btn.config(state="normal", text="▶  RUN PATCHES")
        self._progress["value"] = 100


# ─────────────────────────────────────────────
#  Entry point
# ─────────────────────────────────────────────

if __name__ == "__main__":
    app = MascotEditorApp()
    app.mainloop()
