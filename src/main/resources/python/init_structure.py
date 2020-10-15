class Init_structure:
    def __init__(self, text, angle, length=1.5, color=WHITE):
        self.text = text
        self.angle = angle
        self.length = length
        self.color = color

    def build(self):
        line = Line(color=self.color)
        line.set_length(self.length)
        line.set_angle(self.angle)
        label = TextMobject(self.text, color=self.color)
        label.next_to(line, DOWN, SMALL_BUFF)
        group = VGroup(label, line)
        return group