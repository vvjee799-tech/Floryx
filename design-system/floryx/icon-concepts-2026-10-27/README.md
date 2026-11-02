# Floryx · 图标设计对比

日期：2026-10-27。

## 方向

用户希望图标更贴近 **Floryx**，同时保留原先“一横＋一弧组成 f”的极简构思。品牌依据是项目已有的花开启动画面、`Let ideas bloom` 标语，以及 `docs/assets/floryx-title-v2.svg` 的植物绿和纸白配色。这是设计关联，不是对 Floryx 词源的断言。

## 两份设计稿

- **A · 初芽 / 自由设计**：`a-freeform-bud.png`。细横线、舒展的曲线和叶梢般的上端，强调轻盈与生长。
- **B · 花弧 / Skill 辅助**：`b-skill-bloom.png`。更紧凑的 f、较厚的横线与花瓣负形，强调轮廓辨识度。

两份 PNG 都使用内置 ImageGen 生成，是概念设计稿。A 的首轮在读取任何 SKILL.md 前完成；用户澄清品牌方向后的 A 延续自主构图路线。B 结合 ui-ux-pro-max 的极简风格、负空间、对比和图标轮廓建议，并使用 imagegen skill 组织生成提示。澄清后的两版共享本次对话中的品牌资料，不是严格的隔离实验。

## B 的技能依据

- ui-ux-pro-max 搜索：`minimalism geometric monochrome --domain style -n 2`。
- 已核对结果：`minimalism-and-swiss-style`，采用几何简化、留白、对比和减少装饰；未套用网页网格、字体或交互规范。
- `references/pro-rules.md`：参考图标线条、对比和尺度一致性。
- imagegen：使用 logo-brand 提示结构、明确参考图角色、保持核心构思。

## 提示词

### A

```text
Create a new refined app icon for Floryx, developing the attached original idea toward the NAME and its existing brand spirit "Let ideas bloom." Design A, an intuitive organic direction called Bud. The original icon is a crossbar plus a rising arc; evolve that into a recognizable lowercase f with a subtle botanical personality.

Single centered logo mark on a warm paper #F9F7F2 square background. The mark is deep botanical green #345644, matching Floryx's established flower-and-serif visual identity. Construct it from just one graceful growing-stem curve and one straight horizontal crossbar. Make the f silhouette clearly readable: a fairly upright curved stem, an upper arch curling to the right, a short lower sweep to the left, and a horizontal bar across the upper-middle. Avoid the long symmetric mathematical integral silhouette in the reference.

Let the upper terminal of the same curved stroke gently broaden into one small unfurling petal, ending in a clean tapered tip. The petal is seamlessly integrated into the f's upper arch, not an attached leaf or separate symbol. The main curve has expressive thick-to-thin modulation like a beautifully cut botanical serif. The crossbar remains a delicate straight line. Keep the resulting mark simple enough to draw from memory and elegant enough to sit beside the word "Floryx". It should suggest growth and blooming without depicting an entire flower or plant. No added leaves, stems, roots or decoration.

Use generous clear space, about 20% on all sides, and optical centering. High contrast, calm minimal luxury, pure flat graphic forms, smooth clean contours. Export-ready square icon, background fills all edges. No text or letters other than the abstract f mark, no wordmark, no captions, no rounded-square object, no mockup, no lighting, no shadows, no gradient, no texture, no 3D. Produce ONE icon only.
```

### B（成功生成的最终提示）

```text
Use case: logo-brand. Design a minimal app icon for Floryx, whose existing visual identity is "Let ideas bloom", warm paper and botanical green.

Use the attached original f icon as the identity reference. Refine it into a compact, clearly readable lowercase f made of one flowing curved form and one straight horizontal crossbar. Integrate a single small unfolding petal into the top curve itself, so the f looks like it is blooming. The top petal should be geometric and elegant with a gently pointed tip and an open curve underneath. Keep the lower stem short and simple with a softly rounded end. The crossbar is substantial, about half as thick as the main curved stroke, for clarity at small icon sizes. Keep the whole silhouette upright, compact, balanced and easy to remember.

Solid warm ivory #F9F7F2 mark on solid deep botanical green #345644 background. Center the mark within the middle 60% of the canvas, with generous clear margins. Square full-bleed flat graphic, just one icon. Smooth crisp vector-like contours. No text, extra letters, full flower, additional leaves, border, shadow, texture, gradient, lighting or mockup.
```
