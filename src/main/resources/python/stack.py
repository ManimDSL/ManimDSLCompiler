class Stack(DataStructure, ABC):
    def __init__(self, ul, ur, ll, lr, aligned_edge, color=WHITE, text_color=WHITE, text_weight=NORMAL,
                 font="Times New Roman"):
        super().__init__(ul, ur, ll, lr, aligned_edge, color, text_color, text_weight, font)
        self.empty = None

    def create_init(self, text=None, creation_style=None):
        if not creation_style:
            creation_style = "ShowCreation"
        empty = InitStructure(text, 0, self.max_width - 2 * MED_SMALL_BUFF, color=self.color,
                               text_color=self.text_color)
        self.empty = empty.all
        empty.all.move_to(np.array([self.width_center, self.lr[1], 0]), aligned_edge=self.aligned_edge)
        self.all.add(empty.all)
        creation_transform = globals()[creation_style]
        return [creation_transform(empty.text), ShowCreation(empty.shape)]

    def push(self, obj, creation_style=None):
        if not creation_style:
            creation_style = "FadeIn"
        animations = []
        obj.all.move_to(np.array([self.width_center, self.ul[1] - 0.1, 0]), UP)
        shrink, scale_factor = self.shrink_if_cross_boundary(obj.all)
        if shrink:
            animations.append([shrink])
        target_width = self.all.get_width() * (scale_factor if scale_factor else 1)
        obj.all.scale(target_width / obj.all.get_width())
        creation_transform = globals()[creation_style]
        animations.append([creation_transform(obj.all)])
        animations.append([ApplyMethod(obj.all.next_to, self.all, np.array([0, 0.25, 0]))])
        return animations

    def pop(self, obj, fade_out=True):
        self.all.remove(obj.all)
        animation = [[ApplyMethod(obj.all.move_to, np.array([self.width_center, self.ul[1] - 0.1, 0]), UP)]]
        if fade_out:
            animation.append([FadeOut(obj.all)])
            enlarge, scale_factor = self.shrink(new_width=self.all.get_width(), new_height=self.all.get_height() + 0.25)
            if enlarge:
                animation.append([enlarge])
        return animation

    def shrink_if_cross_boundary(self, new_obj):
        height = new_obj.get_height()
        if self.will_cross_boundary(height, "TOP"):
            return self.shrink(new_width=self.all.get_width(), new_height=self.all.get_height() + height + 0.4)
        return 0, 1

    def push_existing(self, obj):
        animation = [[ApplyMethod(obj.all.move_to, np.array([self.width_center, self.ul[1] - 0.1, 0]), UP)]]
        enlarge, scale_factor = obj.owner.shrink(new_width=obj.owner.all.get_width(),
                                                 new_height=obj.owner.all.get_height() + 0.25)
        sim_list = list()
        if enlarge:
            sim_list.append(enlarge)
        scale_factor = self.all.get_width() / obj.all.get_width()
        if scale_factor != 1:
            sim_list.append(ApplyMethod(obj.all.scale, scale_factor, {"about_edge": UP}))
        if len(sim_list) != 0:
            animation.append(sim_list)
        animation.append([ApplyMethod(obj.all.next_to, self.all, np.array([0, 0.25, 0]))])
        return animation

    def clean_up(self):
        return [FadeOut(self.all)]


# Object representing a stack instantiation.
class InitStructure:
    def __init__(self, text, angle, length=1.5, color=WHITE, text_color=WHITE, text_weight=NORMAL,
                 font="Times New Roman"):
        self.shape = Line(color=color)
        self.shape.set_length(length)
        self.shape.set_angle(angle)
        if text is not None:
            self.text = Text(text, color=text_color, weight=text_weight, font=font)
            self.text.next_to(self.shape, DOWN, SMALL_BUFF)
            self.all = VGroup(self.text, self.shape)
        else:
            self.all = VGroup(self.shape)
