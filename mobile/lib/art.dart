// High Bytes POS - the illustrations of the client's design (claude.ai design "iWarehouse Mobile POS Redesign"): the truck
// carrying glass (large with its "+1 sale" badge on the sign-in screen, small in the trip's headers), the receipts, the
// banknotes and coins; a cracked pane for breakage in the same style. SVG, so they stay sharp at any size.

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

enum Art { truck, receipt, cash, breakage }

const _truckBody = '''
<rect x="106" y="86" width="166" height="13" rx="3" fill="#e3f1ff"/>
<path d="M34 108 L34 70 Q34 56 48 56 L80 56 Q90 56 95 65 L104 84 L104 108 Z" fill="#ffffff"/>
<path d="M46 64 L78 64 Q83 64 86 69 L94 84 L46 84 Z" fill="#8ec4f0"/>
<rect x="34" y="92" width="8" height="6" rx="2" fill="#f5a54f"/>
<rect x="30" y="98" width="246" height="12" rx="4" fill="#0d2f65"/>
<circle cx="70" cy="112" r="14" fill="#0d2f65"/>
<circle cx="70" cy="112" r="5.5" fill="#c7d9ea"/>
<circle cx="232" cy="112" r="14" fill="#0d2f65"/>
<circle cx="232" cy="112" r="5.5" fill="#c7d9ea"/>''';

String _panes(double stroke, double frame) => '''
<path d="M130 88 L162 30 L194 88" fill="none" stroke="#c7d9ea" stroke-width="$frame" stroke-linejoin="round"/>
<rect x="120" y="40" width="34" height="48" rx="2" transform="rotate(-14 154 88)" fill="#bae0ff" fill-opacity="0.78" stroke="#ffffff" stroke-width="$stroke"/>
<rect x="168" y="30" width="44" height="58" rx="2" transform="rotate(16 168 88)" fill="#bae0ff" fill-opacity="0.70" stroke="#ffffff" stroke-width="$stroke"/>
<rect x="176" y="32" width="44" height="56" rx="2" transform="rotate(16 176 88)" fill="#d6ecff" fill-opacity="0.82" stroke="#ffffff" stroke-width="$stroke"/>''';

const _lastPane = '<rect x="186" y="34" width="44" height="54" rx="2" transform="rotate(16 186 88)" fill="#a0d0fa" fill-opacity="0.72" stroke="#ffffff"';

/// The sign-in screen's truck, 300 x 140, with its badge (the text is escaped by the caller).
String heroTruck(String badge) => '''
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 140">
<ellipse cx="150" cy="128" rx="132" ry="7" fill="#00143c" fill-opacity="0.28"/>
<path d="M30 34 v12 M24 40 h12" fill="none" stroke="#f5a54f" stroke-width="2.6" stroke-linecap="round"/>
<path d="M284 62 v10 M279 67 h10" fill="none" stroke="#ffffff" stroke-width="2.4" stroke-linecap="round"/>
<circle cx="112" cy="22" r="4" fill="#8ec4f0"/>
<circle cx="18" cy="92" r="3" fill="#f5a54f"/>
${_panes(1.4, 3)}
<path d="M190 44 l10 -10 M196 54 l14 -14" transform="rotate(16 176 88)" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" stroke-opacity="0.9"/>
$_lastPane stroke-width="1.4"/>
$_truckBody
<path d="M70 64 L70 84" fill="none" stroke="#ffffff" stroke-width="2"/>
<rect x="214" y="0" width="82" height="26" rx="13" fill="#ffffff"/>
<circle cx="229" cy="13" r="7.5" fill="#18a66a"/>
<path d="M225.5 13.2 l2.4 2.4 l4.6 -4.8" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text x="242" y="17.5" font-family="Inter" font-size="12" font-weight="700" fill="#0d2f65">$badge</text>
</svg>''';

const _smallTruck = '''
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 140">
<ellipse cx="150" cy="128" rx="132" ry="8" fill="#00143c" fill-opacity="0.28"/>
<path d="M284 30 v14 M277 37 h14" fill="none" stroke="#f5a54f" stroke-width="5" stroke-linecap="round"/>
PANES
$_lastPane stroke-width="2.5"/>
$_truckBody
</svg>''';

const _receipt = '''
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 112 64">
<ellipse cx="54" cy="61" rx="46" ry="3.5" fill="#00143c" fill-opacity="0.28"/>
<path d="M100 8 v10 M95 13 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/>
<circle cx="10" cy="20" r="3" fill="#8ec4f0"/>
<rect x="40" y="6" width="44" height="50" rx="4" transform="rotate(10 62 31)" fill="#d6ecff"/>
<path d="M30 4 H70 Q74 4 74 8 V56 L68 52 L62 56 L56 52 L50 56 L44 52 L38 56 L32 52 L26 56 V8 Q26 4 30 4 Z" fill="#ffffff"/>
<rect x="33" y="13" width="24" height="3.5" rx="1.75" fill="#c7d9ea"/>
<rect x="33" y="21" width="32" height="3.5" rx="1.75" fill="#c7d9ea"/>
<rect x="33" y="29" width="20" height="3.5" rx="1.75" fill="#c7d9ea"/>
<rect x="33" y="39" width="34" height="5" rx="2.5" fill="#0878d1"/>
<circle cx="76" cy="46" r="11" fill="#18a66a" stroke="#ffffff" stroke-width="2"/>
<path d="M71 46.2 l3.4 3.4 l6 -6.4" fill="none" stroke="#ffffff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
</svg>''';

const _cash = '''
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 118 64">
<ellipse cx="60" cy="61" rx="50" ry="3.5" fill="#00143c" fill-opacity="0.28"/>
<path d="M14 6 v10 M9 11 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/>
<circle cx="112" cy="14" r="3" fill="#8ec4f0"/>
<rect x="10" y="22" width="68" height="34" rx="4" transform="rotate(-9 44 39)" fill="#a8dfc4"/>
<rect x="18" y="18" width="68" height="34" rx="4" fill="#ddf6ea" stroke="#18a66a" stroke-width="1.6"/>
<rect x="23" y="23" width="58" height="24" rx="2.5" fill="none" stroke="#18a66a" stroke-width="1" stroke-opacity="0.55"/>
<circle cx="52" cy="35" r="7" fill="none" stroke="#0e8553" stroke-width="1.8"/>
<path d="M52 31.5 v7" fill="none" stroke="#0e8553" stroke-width="1.8" stroke-linecap="round"/>
<circle cx="29" cy="35" r="2.2" fill="#18a66a"/>
<circle cx="75" cy="35" r="2.2" fill="#18a66a"/>
<rect x="86" y="44" width="26" height="12" fill="#e08a24"/>
<ellipse cx="99" cy="56" rx="13" ry="4" fill="#c97a1a"/>
<ellipse cx="99" cy="44" rx="13" ry="4" fill="#ffc56e"/>
<rect x="90" y="33" width="26" height="9" fill="#e08a24"/>
<ellipse cx="103" cy="42" rx="13" ry="4" fill="#c97a1a"/>
<ellipse cx="103" cy="33" rx="13" ry="4" fill="#ffd27f"/>
</svg>''';

/// Not in the design: a cracked pane with a warning badge, in its colours and strokes.
const _breakage = '''
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 112 64">
<ellipse cx="56" cy="61" rx="46" ry="3.5" fill="#00143c" fill-opacity="0.28"/>
<path d="M102 8 v10 M97 13 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/>
<circle cx="10" cy="22" r="3" fill="#8ec4f0"/>
<rect x="30" y="7" width="46" height="50" rx="2" transform="rotate(-8 53 32)" fill="#bae0ff" fill-opacity="0.85" stroke="#ffffff" stroke-width="1.6"/>
<path d="M52 29 L43 16 M52 29 L63 18 M52 29 L67 37 M52 29 L45 47 M52 29 L36 33" transform="rotate(-8 53 32)" fill="none" stroke="#ffffff" stroke-width="1.6" stroke-linecap="round"/>
<circle cx="52" cy="29" r="4.5" transform="rotate(-8 53 32)" fill="none" stroke="#ffffff" stroke-width="1.4"/>
<path d="M68 57 l6 -9 l6 9 z" fill="#d6ecff" stroke="#ffffff" stroke-width="1"/>
<path d="M20 57 l4 -6 l6 6 z" fill="#d6ecff" stroke="#ffffff" stroke-width="1"/>
<circle cx="80" cy="42" r="11" fill="#f28c28" stroke="#ffffff" stroke-width="2"/>
<path d="M80 36.5 v6.5" fill="none" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round"/>
<circle cx="80" cy="47.4" r="1.6" fill="#ffffff"/>
</svg>''';

/// An illustration at the size the design gives it in a header.
class ArtView extends StatelessWidget {
  const ArtView(this.art, {super.key, this.label});

  final Art art;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final (svg, w, h) = switch (art) {
      Art.truck => (_smallTruck.replaceFirst('PANES', _panes(2.5, 6)), 124.0, 58.0),
      Art.receipt => (_receipt, 112.0, 64.0),
      Art.cash => (_cash, 118.0, 64.0),
      Art.breakage => (_breakage, 112.0, 64.0),
    };
    return SvgPicture.string(svg, width: w, height: h, semanticsLabel: label, excludeFromSemantics: label == null);
  }
}

/// The sign-in screen's truck with its badge.
class HeroTruck extends StatelessWidget {
  const HeroTruck({super.key, required this.badge});

  final String badge;

  @override
  Widget build(BuildContext context) {
    final text = badge.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    return SvgPicture.string(heroTruck(text), width: 300, height: 140, excludeFromSemantics: true);
  }
}
